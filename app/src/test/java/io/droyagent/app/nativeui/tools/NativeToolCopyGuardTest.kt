package io.droyagent.app.nativeui.tools

import io.droyagent.app.nativeui.data.NativeApiException
import org.junit.Assert.*
import org.junit.Test

class NativeToolCopyGuardTest {
    private val stat = NativeToolStat("/fixture/report.bin", "fixture-version", 4)
    private fun guard(bytes: Long? = 4) = NativeToolCopyGuard(NativeToolCopyPlan(NativeToolSource.Workspace("work-a", "report.bin"), "report.bin", bytes, stat.version, stat.path))
    private fun window(offset: Long, count: Int, eof: Boolean, metadata: NativeToolStat = stat) = NativeToolBytes(metadata, ByteArray(count), offset, eof)
    private fun fails(code: String = "NATIVE_TOOLS_COPY_CHANGED", block: () -> Unit) { assertEquals(code, assertThrows(NativeApiException::class.java, block).code) }
    @Test fun completeContiguousWindowsAndUnchangedFinalStatAreRequired() {
        val copy = guard(); copy.accept(window(0, 2, false)); copy.accept(window(2, 2, true)); copy.finish(stat); assertEquals(4L, copy.offset)
        fails { copy.accept(window(4, 0, true)) }
    }
    @Test fun eachWindowChecksVersionCanonicalPathSizeAndRequestedOffset() {
        for (bad in listOf(window(1, 2, false), window(0, 2, false, stat.copy(version = "new")), window(0, 2, false, stat.copy(path = "/other")), window(0, 2, false, stat.copy(bytes = 5))))
            fails { guard().accept(bad) }
    }
    @Test fun truncatedOverflowingAndStalledReadsNeverLookComplete() {
        fails { guard().accept(window(0, 2, true)) }; fails { guard().accept(window(0, 5, true)) }
        fails { guard().accept(window(0, 0, false)) }; fails { guard().finish(stat) }
        fails { guard().accept(window(0, NativeToolCopyGuard.WINDOW + 1, false)) }
    }
    @Test fun aChangeAfterTheLastByteStillRejectsThePrivateCopy() {
        val copy = guard(); copy.accept(window(0, 4, true)); fails { copy.finish(stat.copy(version = "changed-after-read")) }
    }
    @Test fun unknownSizeWaitsForRealEofAndEmptyFilesAreValid() {
        val unknown = guard(null); val metadata = stat.copy(bytes = null)
        unknown.accept(window(0, 3, false, metadata)); fails { unknown.finish(metadata) }; unknown.accept(window(3, 2, true, metadata)); unknown.finish(metadata)
        val empty = guard(0); empty.accept(window(0, 0, true, stat.copy(bytes = 0))); empty.finish(stat.copy(bytes = 0))
        fails("NATIVE_TOOLS_EXPORT_TOO_LARGE") { guard(NativeToolCopyGuard.LIMIT + 1) }
    }
}
