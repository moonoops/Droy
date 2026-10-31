package io.droyagent.app;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Keeps a question draft while its overlay is folded, moved, or temporarily hidden. */
final class PetQuestionDraft {
    private static final java.util.regex.Pattern EDGE_SPACE = java.util.regex.Pattern.compile("^[\\s\\p{Z}\\uFEFF]+|[\\s\\p{Z}\\uFEFF]+$");
    static String trimCustom(String value) { return EDGE_SPACE.matcher(value).replaceAll(""); }
    static final class Answer {
        final JSONObject question;
        final Set<String> selected = new LinkedHashSet<>();
        String custom = "";
        boolean skipped;
        Answer(JSONObject question) { this.question = question; }
        boolean multiple() { return question.optBoolean("multiSelect"); }
        void choose(String label, boolean checked) {
            skipped = false;
            if (!multiple()) { selected.clear(); custom = ""; }
            if (checked) selected.add(label); else selected.remove(label);
        }
        void type(String value) {
            custom = value; skipped = false;
            if (!multiple()) selected.clear();
        }
        void skip() { selected.clear(); custom = ""; skipped = true; }
        boolean complete() { return skipped || !selected.isEmpty() || !trimCustom(custom).isEmpty(); }
    }
    final String key;
    final List<Answer> answers = new ArrayList<>();
    PetQuestionDraft(String key, JSONArray questions) {
        this.key = key;
        for (int i = 0; i < questions.length(); i++) answers.add(new Answer(questions.optJSONObject(i)));
    }
    boolean complete() { return answers.stream().allMatch(Answer::complete); }
    JSONArray encodeSkipped() throws JSONException {
        JSONArray result = new JSONArray();
        for (Answer answer : answers) result.put(new JSONObject().put("id", answer.question.getString("id"))
            .put("selected", new JSONArray()));
        return result;
    }
    JSONArray encode() throws JSONException {
        JSONArray result = new JSONArray();
        for (Answer answer : answers) {
            JSONObject value = new JSONObject().put("id", answer.question.getString("id"))
                .put("selected", new JSONArray(answer.selected));
            String custom = trimCustom(answer.custom);
            if (!custom.isEmpty()) value.put("custom", custom);
            result.put(value);
        }
        return result;
    }
}
