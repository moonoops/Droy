# Local embedding

The private `/embedding` ProductBridge route uses the same authenticated loopback
bridge as other native product services. It never downloads a model, accepts a
model path, logs input text, or stores text/vector results. The memory service
must validate memory-space ownership and epoch before and after the call.

`tools/prepare_embedding.py` verifies a pinned official multilingual E5-small
quantized ONNX model and SentencePiece model, then builds a small JNI library
against the ONNX Runtime already shipped by sherpa. Do not add another
`onnxruntime-android` AAR: its native library would conflict with the speech
runtime. The build verifies the existing ORT version before linking.

The tokenizer follows the official **slow XLMRobertaTokenizer** contract:
SentencePiece normalization, fairseq id offset, special-token splitting, BOS/EOS,
and truncation to 512 tokens. The Hugging Face fast tokenizer can retain different
whitespace around literal special tokens; it is not the reference for this
implementation. Query/document prefixes are applied natively. Inference includes
zero token-type ids, attention-mask mean pooling and L2 normalization. The model
and processing fingerprint must be part of every vector index key.

There is one worker and at most two queued calls. Each call contains at most four
texts. Cancellation/deadline expiration prevents a late result being returned,
but an already executing ORT inference may finish internally. Shared model
weights contain no companion state; results are never cached across spaces.

Host tests cover cancellation, expired queued work, duplicate admission and
resource-integrity checks. `LocalEmbeddingDeviceTest`, enabled explicitly with
`embeddingFixture=true` on the development package, checks nine tokenization
fixtures, six normalized vectors against a pinned host reference, and the native
request contract. Fixtures use invented text only. It does not establish physical
phone memory, battery or sustained-performance acceptance.

Sources:

- https://huggingface.co/intfloat/multilingual-e5-small
- https://github.com/google/sentencepiece/tree/e0cce7d37b065b5140349dbe12c6bcf6192fdd78
- https://github.com/microsoft/onnxruntime/tree/v1.28.2
