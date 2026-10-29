// Host-side checks for the native parser hardening. Builds against the real ggml.c and
// whisper.cpp with AddressSanitizer. Each case writes a crafted input and reports whether
// the parser rejected it cleanly. A sanitizer report means FAIL (the process aborts).
#include <cstdio>
#include <cstdint>
#include <cstring>
#include <string>
#include <vector>
#include "ggml.h"
#include "whisper.h"

typedef std::vector<uint8_t> Buf;
template <class T> static void put(Buf &b, T v) { const uint8_t *p = (const uint8_t *)&v; b.insert(b.end(), p, p + sizeof(T)); }
static void putstr64(Buf &b, const char *s) { put<uint64_t>(b, strlen(s)); b.insert(b.end(), s, s + strlen(s)); }

static bool write_file(const char *path, const Buf &b) {
    FILE *f = fopen(path, "wb"); if (!f) return false; fwrite(b.data(), 1, b.size(), f); fclose(f); return true;
}

static Buf gguf_header(uint64_t n_tensors) {
    Buf b; put<uint32_t>(b, 0x46554747); put<uint32_t>(b, 3); put<uint64_t>(b, n_tensors); put<uint64_t>(b, 0); return b;
}

static int gguf_try(const char *name, const Buf &b, bool expect_ok) {
    const char *path = "/tmp/nf_case.gguf"; write_file(path, b);
    struct gguf_init_params p = { true, nullptr };
    struct gguf_context *c = gguf_init_from_file(path, p);
    bool ok = c != nullptr;
    if (c) gguf_free(c);
    printf("%s %s: parser %s (expected %s)\n", ok == expect_ok ? "PASS" : "FAIL", name, ok ? "accepted" : "rejected", expect_ok ? "accept" : "reject");
    return ok == expect_ok ? 0 : 1;
}

static Buf whisper_prefix(int32_t n_mel, int32_t n_fft, int32_t n_vocab_file) {
    Buf b; put<uint32_t>(b, 0x67676d6c);
    int32_t hp[11] = {51865, 1500, 384, 6, 4, 448, 384, 6, 4, 80, 1};
    for (int i = 0; i < 11; i++) put<int32_t>(b, hp[i]);
    put<int32_t>(b, n_mel); put<int32_t>(b, n_fft);
    if (n_mel > 0 && n_mel <= 1024 && n_fft > 0 && n_fft <= 4096) for (int i = 0; i < n_mel * n_fft; i++) put<float>(b, 0.f);
    put<int32_t>(b, n_vocab_file);
    for (int i = 0; i < n_vocab_file && i < 100; i++) { put<uint32_t>(b, 1); b.push_back('a' + (i % 26)); }
    return b;
}

static int whisper_try(const char *name, Buf b) {
    struct whisper_context_params cp = whisper_context_default_params();
    cp.use_gpu = false;
    struct whisper_context *c = whisper_init_from_buffer_with_params_no_state(b.data(), b.size(), cp);
    bool ok = c != nullptr;
    if (c) whisper_free(c);
    printf("%s %s: loader %s (expected reject)\n", ok ? "FAIL" : "PASS", name, ok ? "accepted" : "rejected");
    return ok ? 1 : 0;
}

int main(int argc, char **argv) {
    setvbuf(stdout, nullptr, _IONBF, 0);
    std::string which = argc > 1 ? argv[1] : "all";
    int fails = 0;
    if (which == "all" || which == "gguf") {
        { Buf b = gguf_header(1); putstr64(b, "w"); put<uint32_t>(b, 200); for (int i = 0; i < 200; i++) put<int64_t>(b, 1); put<uint32_t>(b, 0); put<uint64_t>(b, 0); fails += gguf_try("gguf n_dims=200", b, false); }
        { Buf b = gguf_header(1); putstr64(b, "w"); put<uint32_t>(b, 5); for (int i = 0; i < 5; i++) put<int64_t>(b, 1); put<uint32_t>(b, 0); put<uint64_t>(b, 0); fails += gguf_try("gguf n_dims=5", b, false); }
        { Buf b = gguf_header(1ull << 40); fails += gguf_try("gguf n_tensors=2^40", b, false); }
        { Buf b = gguf_header(3); putstr64(b, "w"); fails += gguf_try("gguf truncated infos (n_tensors=3)", b, false); }
        { Buf b = gguf_header(1); putstr64(b, "w"); put<uint32_t>(b, 2); put<int64_t>(b, 2); put<int64_t>(b, 2); put<uint32_t>(b, 0); put<uint64_t>(b, 0); while (b.size() % 32) b.push_back(0); for (int i = 0; i < 4; i++) put<float>(b, 1.f); fails += gguf_try("gguf valid 2x2 f32 (control)", b, true); }
    }
    if (which == "all" || which == "whisper") {
        { Buf b = whisper_prefix(80, 201, 100); put<int32_t>(b, 100); put<int32_t>(b, 5); put<int32_t>(b, 0); for (int i = 0; i < 100; i++) put<int32_t>(b, 1); b.insert(b.end(), {'a','b','c','d','e'}); fails += whisper_try("whisper tensor n_dims=100", b); }
        { Buf b = whisper_prefix(80, 201, 100); put<int32_t>(b, 2); put<int32_t>(b, -5); put<int32_t>(b, 0); put<int32_t>(b, 7); fails += whisper_try("whisper negative name length", b); }
        { Buf b = whisper_prefix(80, 201, 100); put<int32_t>(b, 2); put<int32_t>(b, 1 << 30); put<int32_t>(b, 0); put<int32_t>(b, 7); fails += whisper_try("whisper huge name length", b); }
        { Buf b = whisper_prefix(80, 201, 100); put<int32_t>(b, 2); put<int32_t>(b, 8); put<int32_t>(b, 0); put<int32_t>(b, 3); put<int32_t>(b, 3); b.insert(b.end(), {'a','b'}); fails += whisper_try("whisper truncated tensor name", b); }
        { Buf b = whisper_prefix(80, 201, 100); put<int32_t>(b, 2); put<int32_t>(b, 4); put<int32_t>(b, 0); put<int32_t>(b, 0x7fffffff); put<int32_t>(b, 2); fails += whisper_try("whisper dimension overflow", b); }
        { Buf b = whisper_prefix(0x7fffffff, 0x7fffffff, 0); fails += whisper_try("whisper huge mel filter dims", b); }
        { Buf b = whisper_prefix(80, 201, 0); b.resize(b.size() - 4); put<int32_t>(b, 0x7fffffff); fails += whisper_try("whisper huge vocab count", b); }
        { Buf b = whisper_prefix(80, 201, 0); b.resize(b.size() - 4); put<int32_t>(b, 3); put<uint32_t>(b, 0xffffffffu); fails += whisper_try("whisper huge vocab token length", b); }
        { Buf b = whisper_prefix(80, 201, 100); b.resize(b.size() / 2); fails += whisper_try("whisper truncated mid-file", b); }
    }
    printf("%s: %d failing case(s)\n", fails ? "RESULT FAIL" : "RESULT OK", fails);
    return fails ? 1 : 0;
}
