package io.droyagent.app.nativeui.schedule

import io.droyagent.app.nativeui.data.NativeApiException
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal object NativeScheduleEdits {
    private val timeFormat = DateTimeFormatter.ofPattern("HH:mm:ss.SSS", java.util.Locale.ROOT)

    fun edit(entry: NativeScheduleEntry, zone: String): NativeScheduleDraft {
        val timing = timing(entry.record, zone)
        return NativeScheduleDraft(entry.sessionId, entry.record.title, entry.record.prompt, timing, entry.record, timing)
    }

    fun create(sessionId: String?, now: Instant, zone: String): NativeScheduleDraft {
        val next = now.plusSeconds(3600).atZone(ZoneId.of(zone))
        val timing = NativeScheduleTiming("at", next.toLocalDate().toString(), timeFormat.format(next), zone)
        return NativeScheduleDraft(sessionId, "", "", timing, originalTiming = timing)
    }

    fun timing(record: NativeScheduleRecord, zone: String): NativeScheduleTiming = when (record.kind) {
        "after", "at" -> Instant.parse(record.scheduledAt).atZone(ZoneId.of(zone)).let {
            NativeScheduleTiming("at", it.toLocalDate().toString(), timeFormat.format(it), zone)
        }
        "every" -> NativeScheduleTiming("every", seconds = requireNotNull(record.everySeconds).toString(), timeZone = zone)
        "daily", "weekly" -> NativeScheduleTiming(record.kind, time = requireNotNull(record.time), timeZone = requireNotNull(record.timeZone), weekdays = record.weekdays)
        "cron" -> NativeScheduleTiming("cron", timeZone = requireNotNull(record.timeZone), expression = requireNotNull(record.expression))
        else -> throw NativeApiException("malformed_response")
    }

    fun validate(draft: NativeScheduleDraft, now: Instant) {
        if (draft.sessionId.isNullOrBlank()) throw NativeApiException("schedule_target_required")
        if (draft.title.trim().isEmpty() || draft.title.trim().length > 120 || draft.prompt.trim().isEmpty()) throw NativeApiException("invalid_prompt")
        if (draft.creating || draft.timing != draft.originalTiming) validateTiming(draft.timing, draft.creating, now)
    }

    fun update(draft: NativeScheduleDraft): NativeScheduleUpdate? {
        val original = draft.expected ?: return null
        val title = draft.title.trim().takeIf { it != original.title }
        val prompt = draft.prompt.trim().takeIf { it != original.prompt }
        val change = draft.timing.takeIf { it != draft.originalTiming }
        if (title == null && prompt == null && change == null) return null
        return NativeScheduleUpdate(NativeScheduleKey(requireNotNull(draft.sessionId), original.id), original, title, prompt, change)
    }

    /** A user-triggered rebase carries only the fields the user actually changed. */
    fun rebase(draft: NativeScheduleDraft, current: NativeScheduleEntry, zone: String): NativeScheduleDraft {
        val old = draft.expected ?: return draft
        val fresh = edit(current, zone)
        return fresh.copy(title = if (draft.title.trim() != old.title) draft.title else fresh.title,
            prompt = if (draft.prompt.trim() != old.prompt) draft.prompt else fresh.prompt,
            timing = if (draft.timing != draft.originalTiming) draft.timing else fresh.timing)
    }

    fun matches(draft: NativeScheduleDraft, record: NativeScheduleRecord): Boolean {
        if (draft.title.trim() != record.title || draft.prompt.trim() != record.prompt) return false
        val expected = draft.expected
        if (expected != null && draft.timing == draft.originalTiming) {
            return sameStoredRule(expected, record)
        }
        val rule = draft.timing
        return when (rule.kind) {
            "after" -> record.kind == "after" && record.afterSeconds == rule.seconds.toLongOrNull()
            "at" -> record.kind in setOf("after", "at") && localInstant(rule)?.toString() == Instant.parse(record.scheduledAt).toString()
            "every" -> record.kind == "every" && record.everySeconds == rule.seconds.toLongOrNull()
            "daily", "weekly" -> record.kind == rule.kind && sameTime(rule.time, record.time) && sameZone(rule.timeZone, record.timeZone)
                && (rule.kind != "weekly" || record.weekdays == rule.weekdays.distinct().sorted())
            "cron" -> record.kind == "cron" && rule.expression.trim().split(Regex("\\s+")).joinToString(" ") == record.expression && sameZone(rule.timeZone, record.timeZone)
            else -> false
        }
    }

    private fun sameStoredRule(a: NativeScheduleRecord, b: NativeScheduleRecord): Boolean = when {
        a.kind != b.kind -> false
        a.kind in setOf("after", "at") -> a.scheduledAt == b.scheduledAt
        a.kind == "every" -> a.everySeconds == b.everySeconds
        a.kind in setOf("daily", "weekly") -> a.time == b.time && a.timeZone == b.timeZone && a.weekdays == b.weekdays
        a.kind == "cron" -> a.expression == b.expression && a.timeZone == b.timeZone
        else -> false
    }

    fun normalizedTime(text: String): String = try {
        if (!text.trim().matches(Regex("\\d{2}:\\d{2}(?::\\d{2}(?:\\.\\d{1,3})?)?"))) throw NativeApiException("invalid_rule")
        timeFormat.format(LocalTime.parse(text.trim()))
    }
        catch (error: DateTimeException) { throw NativeApiException("invalid_rule", error) }

    private fun validateTiming(value: NativeScheduleTiming, creating: Boolean, now: Instant) {
        if (value.kind !in (if (creating) setOf("after", "at", "every", "daily", "weekly", "cron") else setOf("at", "every", "daily", "weekly", "cron")))
            throw NativeApiException("invalid_selector")
        if (value.kind in setOf("after", "every")) {
            val seconds = value.seconds.toLongOrNull() ?: throw NativeApiException("invalid_rule")
            if (seconds !in 1..9_007_199_254_740_991L) throw NativeApiException("invalid_rule")
            if (value.kind == "every" && seconds < 60) throw NativeApiException("frequency_too_high")
            return
        }
        if (!value.timeZone.trim().matches(Regex("UTC|[A-Za-z][A-Za-z0-9_+.-]*(?:/[A-Za-z0-9_+.-]+)+"))) throw NativeApiException("invalid_time_zone")
        if (value.kind == "cron") {
            if (value.expression.trim().split(Regex("\\s+")).size != 5) throw NativeApiException("invalid_rule")
            return
        }
        normalizedTime(value.time)
        if (value.kind == "weekly" && (value.weekdays.isEmpty() || value.weekdays.distinct().size != value.weekdays.size || value.weekdays.any { it !in 1..7 }))
            throw NativeApiException("invalid_rule")
        if (value.kind == "at") {
            try { LocalDate.parse(value.date).also { if (it.year !in 1..9999) throw NativeApiException("time_out_of_range") } }
            catch (error: DateTimeException) { throw NativeApiException("invalid_rule", error) }
            val instant = localInstant(value)
            if (instant != null && !instant.isAfter(now)) throw NativeApiException("not_future")
        }
    }

    private fun localInstant(value: NativeScheduleTiming): Instant? {
        val zone = try { ZoneId.of(value.timeZone.trim()) } catch (_: DateTimeException) { return null }
        val local = try { LocalDateTime.of(LocalDate.parse(value.date), LocalTime.parse(value.time.trim())) }
            catch (_: DateTimeException) { return null }
        val offsets = zone.rules.getValidOffsets(local)
        if (offsets.isEmpty()) throw NativeApiException("invalid_rule")
        return local.toInstant(offsets.first())
    }
    private fun sameTime(a: String, b: String?): Boolean = b != null && try { LocalTime.parse(a.trim()) == LocalTime.parse(b) } catch (_: DateTimeException) { false }
    private fun sameZone(a: String, b: String?): Boolean = b != null && (a.trim() == b || try { ZoneId.of(a.trim()).rules == ZoneId.of(b).rules } catch (_: DateTimeException) { false })
}
