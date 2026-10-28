// whisper-cli: minimal offline transcription CLI against the vendored whisper.cpp
// engine (native/jni/src/ggml). Exists only for CI validation of the bundled
// voice model with a known sample. Not part of the released app.
#include "whisper.h"
#include <cstdio>
#include <cstdint>
#include <cstring>
#include <string>
#include <vector>

static bool read_wav_f32(const char * path, std::vector<float> & out, int & rate, int & channels) {
    FILE * f = fopen(path, "rb");
    if (!f) { fprintf(stderr, "cannot open %s\n", path); return false; }
    char riff[12];
    if (fread(riff, 1, 12, f) != 12 || memcmp(riff, "RIFF", 4) || memcmp(riff + 8, "WAVE", 4)) {
        fprintf(stderr, "not a RIFF/WAVE file\n"); fclose(f); return false;
    }
    int16_t fmt_tag = -1; rate = 0; channels = 0; int bits = 0;
    std::vector<uint8_t> pcm;
    for (;;) {
        char id[4]; uint32_t sz;
        if (fread(id, 1, 4, f) != 4) break;
        if (fread(&sz, 1, 4, f) != 4) break;
        if (!memcmp(id, "fmt ", 4)) {
            uint8_t buf[40]; size_t n = sz < sizeof(buf) ? sz : sizeof(buf);
            if (fread(buf, 1, n, f) != n) { fclose(f); return false; }
            if (sz > n) fseek(f, (long)(sz - n), SEEK_CUR);
            fmt_tag = *(int16_t*)(buf);
            channels = *(int16_t*)(buf + 2);
            rate = *(int32_t*)(buf + 4);
            bits = *(int16_t*)(buf + 14);
        } else if (!memcmp(id, "data", 4)) {
            pcm.resize(sz);
            if (fread(pcm.data(), 1, sz, f) != sz) { fclose(f); return false; }
        } else {
            fseek(f, (long)sz, SEEK_CUR);
        }
    }
    fclose(f);
    if (fmt_tag != 1 || bits != 16 || pcm.empty()) {
        fprintf(stderr, "need 16-bit PCM wav (got fmt=%d bits=%d bytes=%zu)\n", fmt_tag, bits, pcm.size());
        return false;
    }
    size_t n = pcm.size() / 2;
    out.resize(n);
    const int16_t * s = (const int16_t*)pcm.data();
    for (size_t i = 0; i < n; i++) out[i] = s[i] / 32768.0f;
    return true;
}

int main(int argc, char ** argv) {
    if (argc != 3) { fprintf(stderr, "usage: %s model.bin audio.wav\n", argv[0]); return 2; }
    std::vector<float> pcmf32; int rate = 0, channels = 0;
    if (!read_wav_f32(argv[2], pcmf32, rate, channels)) return 2;
    if (rate != 16000) { fprintf(stderr, "need 16 kHz mono wav (got %d Hz, %d ch)\n", rate, channels); return 2; }

    whisper_context_params cparams = whisper_context_default_params();
    whisper_context * ctx = whisper_init_from_file_with_params(argv[1], cparams);
    if (!ctx) { fprintf(stderr, "failed to load model %s\n", argv[1]); return 1; }

    whisper_full_params wparams = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    wparams.print_realtime = false;
    wparams.print_progress = false;
    wparams.print_special = false;
    wparams.print_timestamps = false;
    wparams.translate = false;
    wparams.language = "en";
    wparams.n_threads = 4;
    wparams.no_timestamps = true;

    if (whisper_full(ctx, wparams, pcmf32.data(), (int)pcmf32.size()) != 0) {
        fprintf(stderr, "whisper_full failed\n"); return 1;
    }
    std::string all;
    const int n = whisper_full_n_segments(ctx);
    for (int i = 0; i < n; i++) all += whisper_full_get_segment_text(ctx, i);
    printf("TRANSCRIPT:%s\n", all.c_str());
    whisper_free(ctx);
    return 0;
}
