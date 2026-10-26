package io.droyagent.androiduse;

import org.json.JSONObject;

/** Internal native protocol. Inputs come from the validated memory tools, not model parameters. */
public interface MemoryBackend {
    interface BeforeWrite {
        void prepared(JSONObject helperIdentity) throws Exception;
    }

    JSONObject request(JSONObject request, Cancellation cancellation, BeforeWrite beforeWrite) throws Exception;

    /** Describes the packaged backend without starting su or requesting authorization. */
    JSONObject capabilities() throws Exception;
}
