/* XAudio2 2.7 shim (the June 2010 SDK runtime LEGO Marvel was built with):
 * IXAudio2 and its voices are host COM objects, mixed in software on an
 * engine thread that runs the guest's voice and engine callbacks, and queued
 * to SDL. Effects (XAPOFX reverb/EQ) and filters are accepted and ignored.
 *
 * Callbacks run with g_lock released: the game submits buffers from inside
 * them and calls into XAudio2 from its own threads while holding its locks.
 * A pass is: OnProcessingPassStart / OnVoiceProcessingPassStart (unlocked),
 * mix (locked, buffer events recorded), then the recorded events (unlocked). */
#include "hcom.h"
#include <SDL2/SDL.h>
#include <libavcodec/avcodec.h>
#include <libavutil/channel_layout.h>
#include <libavutil/frame.h>
#include <libavutil/mem.h>
#include <math.h>
#include <pthread.h>
#include <stdio.h>
#include <time.h>
#include <unistd.h>

#define PASS_FRAMES_MS 10
#define MAX_VOICES 1024
#define MAX_QUEUE 64
#define MAX_CH 8
#define MAX_SENDS 8

enum { V_SOURCE, V_SUBMIX, V_MASTER };
enum { FMT_PCM16, FMT_PCM8, FMT_F32, FMT_PCM24, FMT_PCM32, FMT_ADPCM, FMT_WMA, FMT_NONE };

typedef struct {
    uint32_t flags, bytes, data, play_begin, play_len, loop_begin, loop_len, loop_count, ctx;
    int16_t *decoded;   /* ADPCM/xWMA decoded at submit */
    uint32_t nframes;   /* frames in the whole buffer (PCM/ADPCM/xWMA) */
    uint32_t pos, end;  /* current frame, end of the play region */
    int loops_left;     /* 255 = forever */
    int started;
    /* xWMA is decoded in full by a worker thread outside g_lock: the mixer
     * only copies frames, so it can never run out mid-stream. */
    uint32_t wma_off;   /* next compressed byte (decode cursor) */
    uint32_t pcm_n, pcm_rd, pcm_cap;  /* decoded frames, read cursor, capacity */
    int wma_done;       /* decode finished: pcm_n frames are valid */
    int decoding;       /* 1: worker is decoding this buffer, 2: cancelled */
    uint32_t id;        /* log id */
} XBuf;

typedef struct Voice Voice;
struct Voice {
    int kind, dead;
    uint32_t guest, callback, flags;
    int in_ch, rate, stage;
    /* source format */
    int fmt, bpf, block_align, spb;
    uint8_t adpcm_coef[64];
    int ncoef;
    AVCodecContext *wma;   /* xWMA (WMAudio2) decoder */
    float ratio, max_ratio;
    int running;
    XBuf q[MAX_QUEUE];
    int qhead, qn;
    uint64_t played;
    double frac;
    float prev[MAX_CH], cur[MAX_CH];
    int primed;
    /* mixing */
    float volume, chvol[MAX_CH];
    int nsends;
    Voice *send[MAX_SENDS];
    float matrix[MAX_SENDS][MAX_CH * MAX_CH];
    int matrix_set[MAX_SENDS];
    float *acc;         /* input accumulation for submix/master: in_ch * frames */
};

static HComClass k_xa, k_src, k_sub, k_mst, k_fx;
static pthread_mutex_t g_lock;
static Voice *g_voices[MAX_VOICES];
static Voice *g_master;
static uint32_t g_engine_cb[16];
static int g_nengine_cb;
static int g_rate = 48000, g_running, g_thread_started;
static SDL_AudioDeviceID g_dev;
static uint32_t g_dev_buf_bytes;   /* one OpenSL buffer, in device-format bytes */
static uint32_t g_dev_bytes_per_ms = 384;  /* 48 kHz stereo F32 default */

/* ------------------------------------------------------ audio diagnostics
 * Enabled by an empty save/audiodump.txt. Writes raw int16 PCM files next to
 * it and logs mixer health, so decode, mix and device problems can be told
 * apart from the PC. The files are only written while the marker exists. */
static uint64_t now_us(void);
static int g_audiodump = -1;
static FILE *g_mix_dump;
static unsigned long long g_mix_dump_left;
static unsigned long long g_silence_frames, g_dry_passes;
static unsigned long long g_pass_window_max_us;
static uint32_t g_qmin = ~0u, g_qmax;
static uint64_t g_stat_next_us, g_mix_start_us, g_q_ignore_until;

static int audiodump_enabled(void) {
    if (g_audiodump < 0) {
        extern char g_save_dir[];
        char path[1100];
        snprintf(path, sizeof path, "%s/audiodump.txt", g_save_dir);
        g_audiodump = access(path, F_OK) == 0;
        if (g_audiodump) LOG("xaudio2: audio dump on (%s)", path);
    }
    return g_audiodump;
}

static void audiodump_begin(void) {
    if (!audiodump_enabled() || g_mix_dump) return;
    extern char g_save_dir[];
    char path[1100];
    snprintf(path, sizeof path, "%s/mix_dump.raw", g_save_dir);
    FILE *f = fopen(path, "wb");
    if (!f) return;
    g_mix_dump = f;
    g_mix_dump_left = (unsigned long long)g_rate * 40;  /* 40 s, stereo */
    LOG("xaudio2: dumping mixed audio to %s", path);
}

static void audiodump_mix(const float *out, int frames) {
    if (!g_mix_dump || !g_mix_dump_left) return;
    int16_t buf[512 * 2];
    int done = 0;
    while (done < frames && g_mix_dump_left) {
        int n = frames - done;
        if (n > 512) n = 512;
        if ((unsigned long long)n > g_mix_dump_left) n = (int)g_mix_dump_left;
        for (int i = 0; i < n * 2; i++) {
            float s = out[(size_t)done * 2 + i];
            if (s < -1.0f) s = -1.0f;
            else if (s > 1.0f) s = 1.0f;
            buf[i] = (int16_t)(s * 32767.0f);
        }
        fwrite(buf, sizeof(int16_t), (size_t)n * 2, g_mix_dump);
        g_mix_dump_left -= (unsigned)n;
        done += n;
    }
    if (!g_mix_dump_left) {
        fclose(g_mix_dump);
        g_mix_dump = NULL;
        LOG("xaudio2: mixed audio dump complete");
    }
}

static void audiodump_wma(uint32_t id, const int16_t *pcm, uint32_t frames, int ch, int rate) {
    if (!audiodump_enabled() || id > 2 || !pcm || !frames) return;
    extern char g_save_dir[];
    char path[1100];
    snprintf(path, sizeof path, "%s/wma_%u_%dch_%d.raw", g_save_dir, id, ch, rate);
    FILE *f = fopen(path, "wb");
    if (!f) return;
    uint64_t cap = (uint64_t)(rate > 0 ? rate : 48000) * 30;
    if (frames > cap) frames = (uint32_t)cap;
    fwrite(pcm, sizeof(int16_t), (size_t)frames * (size_t)ch, f);
    fclose(f);
    LOG("xaudio2: dumped xWMA buffer %u (%u frames) to %s", id, frames, path);
}

static const uint8_t CLSID_XAudio2[16] = {0x85, 0x86, 0x50, 0x5a, 0x54, 0xa2, 0xba, 0x4f, 0x9b, 0x82, 0x9a, 0x24, 0xb0, 0x03, 0x06, 0xaf};
/* XAudio2 2.6 (Skyrim): same interfaces as 2.7, different class ids */
static const uint8_t CLSID_XAudio2_26[16] = {0x49, 0x9b, 0xda, 0x3e, 0x85, 0x20, 0x8b, 0x49, 0x9b, 0xb2, 0x39, 0xa6, 0x77, 0x84, 0x93, 0xde};
static const uint8_t CLSID_XAudio2_26_Debug[16] = {0x94, 0x98, 0x19, 0x47, 0xc2, 0x7c, 0x4d, 0x44, 0x98, 0x73, 0xce, 0xd2, 0x56, 0x2c, 0xc6, 0x0e};
static const uint8_t CLSID_XAudio2_Debug[16] = {0x35, 0xea, 0x05, 0xdb, 0x29, 0x03, 0x4b, 0x4d, 0xa5, 0x3a, 0x6d, 0xea, 0xd0, 0x3d, 0x38, 0x52};

/* ---------------------------------------------------------------- format */
static int16_t adpcm_clamp(int v) { return (int16_t)(v < -32768 ? -32768 : v > 32767 ? 32767 : v); }

static const int k_adpcm_adapt[16] = {230, 230, 230, 230, 307, 409, 512, 614, 768, 614, 512, 409, 307, 230, 230, 230};

/* MS ADPCM: one block -> spb frames of in_ch samples. */
static void adpcm_block(Voice *v, const uint8_t *b, int16_t *out) {
    int ch = v->in_ch, spb = v->spb;
    int pred[2], delta[2], s1[2], s2[2];
    const int16_t *coef = (const int16_t *)v->adpcm_coef;
    for (int c = 0; c < ch; c++) pred[c] = b[c] < v->ncoef ? b[c] : 0;
    b += ch;
    for (int c = 0; c < ch; c++, b += 2) delta[c] = (int16_t)(b[0] | b[1] << 8);
    for (int c = 0; c < ch; c++, b += 2) s1[c] = (int16_t)(b[0] | b[1] << 8);
    for (int c = 0; c < ch; c++, b += 2) s2[c] = (int16_t)(b[0] | b[1] << 8);
    for (int c = 0; c < ch; c++) { out[c] = (int16_t)s2[c]; out[ch + c] = (int16_t)s1[c]; }
    int n = 2 * ch, total = spb * ch, hi = 1;
    while (n < total) {
        int c = n % ch;
        int nib = hi ? (*b >> 4) : (*b & 15);
        if (!hi) b++;
        hi = !hi;
        int sn = nib & 8 ? nib - 16 : nib;
        int p = (s1[c] * coef[2 * pred[c]] + s2[c] * coef[2 * pred[c] + 1]) / 256 + sn * delta[c];
        int16_t s = adpcm_clamp(p);
        s2[c] = s1[c];
        s1[c] = s;
        out[n++] = s;
        delta[c] = k_adpcm_adapt[nib] * delta[c] / 256;
        if (delta[c] < 16) delta[c] = 16;
    }
}

/* xWMA (WAVEFORMATEX tag 0x0161): the game submits WMAv2 bitstream buffers.
 * FFmpeg's wmav2 decoder reads the codec flags from extradata bytes 4-5, so
 * the WAVEFORMATEX cbSize extra data goes in as extradata; block_align is
 * mandatory. */
static void init_wma(Voice *v, uint32_t wf) {
    const AVCodec *codec = avcodec_find_decoder(AV_CODEC_ID_WMAV2);
    if (!codec) {
        LOG("xaudio2: no WMAv2 decoder in this build");
        return;
    }
    AVCodecContext *ctx = avcodec_alloc_context3(codec);
    if (!ctx) return;
    ctx->sample_rate = v->rate;
    av_channel_layout_default(&ctx->ch_layout, v->in_ch);
    ctx->block_align = v->block_align;
    ctx->bit_rate = (int64_t)M32(wf + 8) * 8;
    /* Skyrim's WAVEFORMATEX has cbSize=0, so the WMA flags2 byte is missing.
     * A 6-byte extradata with 0x1f is used; 0x1f is the flags2 value the
     * xWMA streams need. */
    int cb = M16(wf + 16);
    if (cb >= 6) {
        ctx->extradata = av_mallocz((size_t)cb + AV_INPUT_BUFFER_PADDING_SIZE);
        if (ctx->extradata) {
            memcpy(ctx->extradata, MEM + wf + 18, (size_t)cb);
            ctx->extradata_size = cb;
        }
    } else {
        ctx->extradata = av_mallocz(6 + AV_INPUT_BUFFER_PADDING_SIZE);
        if (ctx->extradata) {
            ctx->extradata[4] = 0x1f;   /* flags2: exp vlc + bit reservoir + variable block */
            ctx->extradata_size = 6;
        }
    }
    ctx->request_sample_fmt = AV_SAMPLE_FMT_FLT;
    if (avcodec_open2(ctx, codec, NULL) < 0) {
        LOG("xaudio2: WMAv2 open failed (ch %d, %d Hz, block %d, cb %d)",
            v->in_ch, v->rate, v->block_align, cb);
        avcodec_free_context(&ctx);
        return;
    }
    v->wma = ctx;
    LOG("xaudio2: xWMA decoder ready (%d ch, %d Hz, block %d, cb %d)",
        v->in_ch, v->rate, v->block_align, cb);
}

static void set_format(Voice *v, uint32_t wf) {
    uint16_t tag = M16(wf);
    int bits = M16(wf + 14);
    v->in_ch = M16(wf + 2);
    if (v->in_ch < 1) v->in_ch = 1;
    if (v->in_ch > MAX_CH) v->in_ch = MAX_CH;
    v->rate = (int)M32(wf + 4);
    v->block_align = M16(wf + 12);
    if (tag == 0xFFFE) {  /* WAVE_FORMAT_EXTENSIBLE: the subformat's first word is the tag */
        tag = M16(wf + 24);
    }
    v->fmt = FMT_NONE;
    if (tag == 1) v->fmt = bits == 8 ? FMT_PCM8 : bits == 24 ? FMT_PCM24 : bits == 32 ? FMT_PCM32 : FMT_PCM16;
    else if (tag == 3) v->fmt = FMT_F32;
    else if (tag == 2) {
        v->fmt = FMT_ADPCM;
        v->spb = M16(wf + 18);
        v->ncoef = M16(wf + 20);
        if (v->ncoef > 16) v->ncoef = 16;
        memcpy(v->adpcm_coef, MEM + wf + 22, (size_t)v->ncoef * 4);
    }
    else if (tag == 0x0161) {
        v->fmt = FMT_WMA;
        init_wma(v, wf);
    }
    if (v->fmt == FMT_NONE) {
        static unsigned s_silent;
        if (++s_silent % 100 == 1)
            LOG("xaudio2: unsupported source format tag %04x (%d ch, %d Hz) plays silent (%u so far)", tag, v->in_ch, v->rate, s_silent);
    }
    v->bpf = v->fmt == FMT_ADPCM ? 2 * v->in_ch : v->block_align ? v->block_align : v->in_ch * bits / 8;
}

/* Appends every frame FFmpeg currently has for ctx to a growable int16 PCM
 * buffer (interleaved, starting at element n). Returns frames appended. */
static uint32_t wma_receive_into(AVCodecContext *ctx, int in_ch,
                                 int16_t **pcm, uint32_t *n, uint32_t *cap) {
    AVFrame *frame = av_frame_alloc();
    if (!frame) return 0;
    uint32_t got = 0;
    int ret;
    while ((ret = avcodec_receive_frame(ctx, frame)) == 0) {
        int ch = frame->ch_layout.nb_channels;
        int nf = frame->nb_samples;
        if (ch < 1) ch = 1;
        if (*n + got + (uint32_t)nf > *cap) {
            uint32_t grow = (*n + got + (uint32_t)nf) * 2 + 4096;
            int16_t *p = realloc(*pcm, sizeof(int16_t) * (size_t)grow * (size_t)in_ch);
            if (!p) { av_frame_unref(frame); break; }
            *pcm = p;
            *cap = grow;
        }
        for (int i = 0; i < nf; i++) {
            for (int c = 0; c < in_ch; c++) {
                int sc = c < ch ? c : ch - 1;
                float s = 0.0f;
                switch (frame->format) {
                case AV_SAMPLE_FMT_FLTP: s = ((const float *)frame->data[sc])[i]; break;
                case AV_SAMPLE_FMT_S16P: s = ((const int16_t *)frame->data[sc])[i] / 32768.0f; break;
                case AV_SAMPLE_FMT_FLT:  s = ((const float *)frame->data[0])[i * ch + sc]; break;
                case AV_SAMPLE_FMT_S16:  s = ((const int16_t *)frame->data[0])[i * ch + sc] / 32768.0f; break;
                default: break;
                }
                if (s < -1.0f) s = -1.0f;
                else if (s > 1.0f) s = 1.0f;
                (*pcm)[((size_t)*n + (size_t)got + (size_t)i) * (size_t)in_ch + (size_t)c] = (int16_t)(s * 32767.0f);
            }
        }
        got += (uint32_t)nf;
        av_frame_unref(frame);
    }
    av_frame_free(&frame);
    return got;
}

/* One buffer handed to the decode thread. Everything the decoder needs is
 * copied here under g_lock; the worker never dereferences the voice or the
 * guest queue, so a flush or voice destruction racing with a decode can only
 * make the result get dropped, never crash. */
typedef struct {
    Voice   *voice;        /* identity only, never dereferenced */
    uint32_t id;
    uint32_t bytes, data;
    int      in_ch, rate, block_align;
    int64_t  bit_rate;
    uint8_t  extradata[64];
    int      extradata_size;
} WmaJob;

/* Decodes a whole submitted xWMA buffer. Runs on the decode thread, outside
 * g_lock: neither the game thread nor the mixer ever waits for this. */
static int16_t *wma_decode_job(const WmaJob *job, uint32_t *frames_out) {
    const AVCodec *codec = avcodec_find_decoder(AV_CODEC_ID_WMAV2);
    if (!codec) return NULL;
    AVCodecContext *ctx = avcodec_alloc_context3(codec);
    if (!ctx) return NULL;
    ctx->sample_rate = job->rate;
    av_channel_layout_default(&ctx->ch_layout, job->in_ch);
    ctx->block_align = job->block_align;
    ctx->bit_rate = job->bit_rate;
    if (job->extradata_size > 0) {
        ctx->extradata = av_mallocz((size_t)job->extradata_size + AV_INPUT_BUFFER_PADDING_SIZE);
        if (ctx->extradata) {
            memcpy(ctx->extradata, job->extradata, (size_t)job->extradata_size);
            ctx->extradata_size = job->extradata_size;
        }
    }
    ctx->request_sample_fmt = AV_SAMPLE_FMT_FLT;
    if (avcodec_open2(ctx, codec, NULL) < 0) {
        avcodec_free_context(&ctx);
        return NULL;
    }

    int16_t *pcm = NULL;
    uint32_t n = 0, cap = 0, off = 0;
    /* xWMA is VBR, but nAvgBytesPerSec gets close; reserving once avoids
     * realloc doubling a whole music track to ~2x its final size. */
    if (job->bit_rate > 0 && job->rate > 0 && job->bytes) {
        uint64_t est = (uint64_t)job->bytes * (uint64_t)job->rate
                     / (uint64_t)(job->bit_rate / 8) + 4096;
        if (est < 0x7FFFFFFFu) {
            pcm = malloc(sizeof(int16_t) * (size_t)est * (size_t)job->in_ch);
            if (pcm) cap = (uint32_t)est;
        }
    }

    uint32_t rejected = 0;
    while (off < job->bytes) {
        uint32_t left = job->bytes - off;
        uint32_t len = job->block_align > 0 ? (uint32_t)job->block_align : left;
        if (len > left) len = left;
        uint8_t *buf = av_malloc((size_t)len + AV_INPUT_BUFFER_PADDING_SIZE);
        if (!buf) break;
        memcpy(buf, MEM + job->data + off, len);
        memset(buf + len, 0, AV_INPUT_BUFFER_PADDING_SIZE);
        off += len;
        AVPacket *pkt = av_packet_alloc();
        if (!pkt) { av_free(buf); break; }
        pkt->data = buf;
        pkt->size = (int)len;
        int ret = avcodec_send_packet(ctx, pkt);
        av_free(buf);
        av_packet_free(&pkt);
        if (ret < 0) { rejected++; continue; }
        uint32_t got = wma_receive_into(ctx, job->in_ch, &pcm, &n, &cap);
        n += got;
    }
    if (rejected && job->id <= 3)
        LOG("xaudio2: WMA packets rejected: %u (%u bytes)", rejected, job->bytes);
    /* Drain the decoder delay so the tail of the stream is not lost. */
    avcodec_send_packet(ctx, NULL);
    uint32_t tail = wma_receive_into(ctx, job->in_ch, &pcm, &n, &cap);
    n += tail;
    avcodec_free_context(&ctx);
    *frames_out = n;
    return pcm;
}

/* One worker per class, jobs in submission order. Two workers keep short
 * sound effects from queueing behind a whole music track: the game submits
 * hundreds of small buffers (footsteps, voices) alongside multi-minute
 * tracks, and a single decode of a track can take ~1s. */
#define WMA_SMALL_BYTES (128u * 1024u)

static void *wma_decode_main(void *arg) {
    int big = (int)(long)arg;
    for (;;) {
        usleep(10000);
        WmaJob job;
        int found = 0;
        pthread_mutex_lock(&g_lock);
        for (int i = 0; i < MAX_VOICES && !found; i++) {
            Voice *v = g_voices[i];
            if (!v || v->dead || v->fmt != FMT_WMA || !v->wma || !v->qn) continue;
            for (int k = 0; k < v->qn; k++) {
                XBuf *b = &v->q[(v->qhead + k) % MAX_QUEUE];
                if (b->wma_done || b->decoding) continue;
                if (big ? (b->bytes <= WMA_SMALL_BYTES) : (b->bytes > WMA_SMALL_BYTES)) continue;
                memset(&job, 0, sizeof job);
                job.voice = v;
                job.id = b->id;
                job.bytes = b->bytes;
                job.data = b->data;
                job.in_ch = v->in_ch;
                job.rate = v->rate;
                job.block_align = v->block_align;
                job.bit_rate = v->wma->bit_rate;
                int es = v->wma->extradata_size;
                if (v->wma->extradata && es > 0) {
                    if (es > (int)sizeof job.extradata) es = (int)sizeof job.extradata;
                    memcpy(job.extradata, v->wma->extradata, (size_t)es);
                    job.extradata_size = es;
                }
                b->decoding = 1;
                found = 1;
                break;
            }
        }
        pthread_mutex_unlock(&g_lock);
        if (!found) continue;

        uint32_t nframes = 0;
        uint64_t t_dec = now_us();
        int16_t *pcm = wma_decode_job(&job, &nframes);
        t_dec = now_us() - t_dec;
        audiodump_wma(job.id, pcm, nframes, job.in_ch, job.rate);
        if (job.bytes > WMA_SMALL_BYTES || t_dec > 50000)
            LOG("xaudio2: xWMA buffer %u decoded %u frames in %ums",
                job.id, nframes, (unsigned)(t_dec / 1000));

        pthread_mutex_lock(&g_lock);
        Voice *v = NULL;
        for (int i = 0; i < MAX_VOICES; i++)
            if (g_voices[i] == job.voice) { v = g_voices[i]; break; }
        XBuf *b = NULL;
        if (v && !v->dead)
            for (int k = 0; k < v->qn; k++) {
                XBuf *cand = &v->q[(v->qhead + k) % MAX_QUEUE];
                if (cand->id == job.id) { b = cand; break; }
            }
        if (b && b->decoding == 1 && !b->wma_done) {
            free(b->decoded);
            b->decoded = pcm;
            b->pcm_n = b->pcm_cap = nframes;
            b->pcm_rd = 0;
            b->nframes = nframes;
            b->pos = 0;
            b->end = nframes;
            b->wma_done = 1;
            if (b->id <= 3)
                LOG("xaudio2: xWMA buffer %u: %u bytes -> %u frames (%d ch, %d Hz)",
                    b->id, b->bytes, nframes, v->in_ch, v->rate);
            pcm = NULL;
        }
        if (b && b->decoding == 1) b->decoding = 0;
        pthread_mutex_unlock(&g_lock);
        free(pcm);
    }
    return NULL;
}

static int read_frame(Voice *v, XBuf *b, uint32_t f, float *out) {
    int ch = v->in_ch;
    if (v->fmt == FMT_WMA) {
        /* Fully decoded by the worker: pure consumption, never pad silence
         * (a gap in the middle of a stream is an audible click). */
        if (b->pcm_rd >= b->pcm_n) return 0;
        for (int c = 0; c < ch; c++) out[c] = b->decoded[b->pcm_rd * ch + c] / 32768.0f;
        b->pcm_rd++;
        return 1;
    }
    if (b->decoded) { for (int c = 0; c < ch; c++) out[c] = b->decoded[f * ch + c] / 32768.0f; return 1; }
    uint32_t a = b->data + f * (uint32_t)v->bpf;
    switch (v->fmt) {
    case FMT_PCM16: for (int c = 0; c < ch; c++) out[c] = (int16_t)M16(a + 2 * c) / 32768.0f; break;
    case FMT_PCM8: for (int c = 0; c < ch; c++) out[c] = ((float)M8(a + c) - 128.0f) / 128.0f; break;
    case FMT_F32: for (int c = 0; c < ch; c++) out[c] = MF32(a + 4 * c); break;
    case FMT_PCM24:
        for (int c = 0; c < ch; c++) {
            int32_t s = (int32_t)(M8(a + 3 * c) | M8(a + 3 * c + 1) << 8 | M8(a + 3 * c + 2) << 16) << 8;
            out[c] = s / 2147483648.0f;
        }
        break;
    case FMT_PCM32: for (int c = 0; c < ch; c++) out[c] = (int32_t)M32(a + 4 * c) / 2147483648.0f; break;
    default: for (int c = 0; c < ch; c++) out[c] = 0; break;
    }
    return 1;
}

/* ---------------------------------------------------------------- events */
typedef struct { uint32_t cb; int method; uint32_t arg; Voice *v; } Event;
#define MAX_EVENTS 8192
static Event g_ev[MAX_EVENTS];
static int g_nev;

static void push_event(Voice *v, int method, uint32_t arg) {
    if (!v->callback || g_nev >= MAX_EVENTS) return;
    g_ev[g_nev++] = (Event){v->callback, method, arg, v};
}

/* Next source frame, advancing through the queue. Returns 0 when starved. */
static int next_frame(Voice *v, float *out) {
    while (v->qn) {
        XBuf *b = &v->q[v->qhead];
        if (v->fmt == FMT_WMA && !b->wma_done) {
            /* The worker is still decoding this buffer. Emit the silence
             * before the track's first frame and do not advance the cursor,
             * so playback starts at sample 0. This can only happen before
             * the buffer starts, never as a gap mid-track. */
            for (int c = 0; c < v->in_ch; c++) out[c] = 0;
            g_silence_frames++;
            return 1;
        }
        if (!b->started) {
            b->started = 1;
            push_event(v, 3, b->ctx);  /* OnBufferStart */
        }
        if (b->pos < b->end) {
            if (read_frame(v, b, b->pos, out)) {
                b->pos++;
                v->played++;
                if (b->pos >= b->end && b->loops_left && b->loop_len) {
                    if (b->loops_left != 255) b->loops_left--;
                    if (v->fmt == FMT_WMA) {
                        /* all PCM is in memory: rewind the read cursor
                         * instead of feeding the decoder again */
                        b->pcm_rd = 0;
                        b->pos = 0;
                        b->end = b->nframes;
                    } else {
                        b->pos = b->loop_begin;
                        b->end = b->loop_begin + b->loop_len;
                        if (b->end > b->nframes) b->end = b->nframes;
                    }
                    push_event(v, 5, b->ctx);  /* OnLoopEnd */
                }
                return 1;
            }
            /* xWMA: decoded frames exhausted */
            b->pos = b->end;
        }
        /* buffer finished */
        if (v->fmt == FMT_WMA && b->loops_left && b->nframes) {
            /* music loops start at 0, and the whole stream is decoded */
            if (b->loops_left != 255) b->loops_left--;
            b->pcm_rd = 0;
            b->pos = 0;
            b->end = b->nframes;
            push_event(v, 5, b->ctx);  /* OnLoopEnd */
            continue;
        }
        push_event(v, 4, b->ctx);  /* OnBufferEnd */
        if (b->flags & 0x40) push_event(v, 2, 0);  /* XAUDIO2_END_OF_STREAM -> OnStreamEnd */
        free(b->decoded);
        v->qhead = (v->qhead + 1) % MAX_QUEUE;
        v->qn--;
    }
    for (int c = 0; c < v->in_ch; c++) out[c] = 0;
    return 0;
}

/* ---------------------------------------------------------------- mixing */
static void default_matrix(int sc, int dc, float *m) {
    memset(m, 0, sizeof(float) * MAX_CH * MAX_CH);
    for (int d = 0; d < dc; d++)
        for (int s = 0; s < sc; s++) {
            float g = 0;
            if (sc == 1) g = 1.0f;
            else if (sc == dc) g = s == d ? 1.0f : 0.0f;
            else if (dc == 2) g = s == 0 ? (d == 0) : s == 1 ? (d == 1) : (s == 2 || s == 3) ? 0.707f : (s % 2 == d) ? 0.707f : 0.0f;
            else if (dc == 1) g = 1.0f / (float)sc;
            else g = s % dc == d ? 1.0f : 0.0f;
            m[d * sc + s] = g;
        }
}

static void route(Voice *v, const float *in, int frames) {
    Voice *dests[MAX_SENDS];
    int n = v->nsends;
    for (int i = 0; i < n; i++) dests[i] = v->send[i];
    if (!n && g_master && v != g_master) { dests[0] = g_master; n = 1; }
    for (int i = 0; i < n; i++) {
        Voice *d = dests[i];
        if (!d || d->dead || !d->acc) continue;
        float m[MAX_CH * MAX_CH];
        if (i < v->nsends && v->matrix_set[i]) memcpy(m, v->matrix[i], sizeof m);
        else default_matrix(v->in_ch, d->in_ch, m);
        for (int s = 0; s < v->in_ch; s++) {
            float g = v->volume * v->chvol[s];
            for (int dc = 0; dc < d->in_ch; dc++) m[dc * v->in_ch + s] *= g;
        }
        for (int f = 0; f < frames; f++)
            for (int dc = 0; dc < d->in_ch; dc++) {
                float sum = 0;
                for (int s = 0; s < v->in_ch; s++) sum += m[dc * v->in_ch + s] * in[f * v->in_ch + s];
                d->acc[f * d->in_ch + dc] += sum;
            }
    }
}

static void mix_source(Voice *v, int frames, float *tmp) {
    float step = v->ratio * (float)v->rate / (float)g_rate;
    int ch = v->in_ch;
    if (!v->primed) {
        next_frame(v, v->cur);
        memcpy(v->prev, v->cur, sizeof v->prev);
        v->primed = 1;
        v->frac = 0;
    }
    for (int f = 0; f < frames; f++) {
        for (int c = 0; c < ch; c++) tmp[f * ch + c] = v->prev[c] + (v->cur[c] - v->prev[c]) * (float)v->frac;
        v->frac += step;
        while (v->frac >= 1.0) {
            memcpy(v->prev, v->cur, sizeof v->prev);
            next_frame(v, v->cur);
            v->frac -= 1.0;
        }
    }
    route(v, tmp, frames);
}

static void engine_pass(CPU *c, int frames, float *out) {
    static float tmp[MAX_CH * 48000 / 10];
    uint32_t args[3];
    /* engine and voice pass-start callbacks, unlocked */
    for (int i = 0; i < g_nengine_cb; i++) {
        args[0] = g_engine_cb[i];
        rt_invoke(c, M32(M32(g_engine_cb[i])), 1, args);
    }
    uint32_t cbs[MAX_VOICES], req[MAX_VOICES];
    int ncb = 0;
    pthread_mutex_lock(&g_lock);
    for (int i = 0; i < MAX_VOICES; i++) {
        Voice *v = g_voices[i];
        if (!v || v->dead || v->kind != V_SOURCE || !v->callback || !v->running) continue;
        /* bytes the voice needs beyond what is queued for this pass */
        uint64_t need = (uint64_t)(frames * v->ratio * (float)v->rate / (float)g_rate) + 2, have = 0;
        for (int k = 0; k < v->qn; k++) { XBuf *b = &v->q[(v->qhead + k) % MAX_QUEUE]; have += b->end - b->pos; }
        cbs[ncb] = v->callback;
        req[ncb++] = have >= need ? 0 : (uint32_t)((need - have) * (uint64_t)(v->fmt == FMT_ADPCM ? v->block_align / (v->spb ? v->spb : 1) + 1 : v->bpf));
    }
    pthread_mutex_unlock(&g_lock);
    for (int i = 0; i < ncb; i++) {
        args[0] = cbs[i];
        args[1] = req[i];
        rt_invoke(c, M32(M32(cbs[i])), 2, args);  /* OnVoiceProcessingPassStart */
    }

    pthread_mutex_lock(&g_lock);
    g_nev = 0;
    for (int i = 0; i < MAX_VOICES; i++) {
        Voice *v = g_voices[i];
        if (v && !v->dead && v->acc) memset(v->acc, 0, sizeof(float) * (size_t)(v->in_ch * frames));
    }
    for (int i = 0; i < MAX_VOICES; i++) {
        Voice *v = g_voices[i];
        if (v && !v->dead && v->kind == V_SOURCE && v->running) mix_source(v, frames, tmp);
    }
    /* submixes in processing-stage order, then the master */
    for (int stage = 0; stage < 64; stage++)
        for (int i = 0; i < MAX_VOICES; i++) {
            Voice *v = g_voices[i];
            if (!v || v->dead || v->kind != V_SUBMIX || v->stage != stage) continue;
            route(v, v->acc, frames);
        }
    if (g_master && g_master->acc) {
        Voice *m = g_master;
        for (int f = 0; f < frames; f++) {
            float l = 0, r = 0;
            for (int ch = 0; ch < m->in_ch; ch++) {
                float s = m->acc[f * m->in_ch + ch] * m->volume * m->chvol[ch];
                if (m->in_ch == 1) l = r = s;
                else if (ch == 0) l += s;
                else if (ch == 1) r += s;
                else if (ch == 2) { l += 0.707f * s; r += 0.707f * s; }
                else if (ch >= 4) { if (ch % 2 == 0) l += 0.707f * s; else r += 0.707f * s; }
            }
            out[2 * f] = l > 1 ? 1 : l < -1 ? -1 : l;
            out[2 * f + 1] = r > 1 ? 1 : r < -1 ? -1 : r;
        }
    } else memset(out, 0, sizeof(float) * 2 * (size_t)frames);
    int nev = g_nev;
    static Event ev[MAX_EVENTS];
    memcpy(ev, g_ev, sizeof(Event) * (size_t)nev);
    pthread_mutex_unlock(&g_lock);

    for (int i = 0; i < nev; i++) {
        if (ev[i].v->dead) continue;
        uint32_t fn = M32(M32(ev[i].cb) + 4 * (uint32_t)ev[i].method);
        args[0] = ev[i].cb;
        args[1] = ev[i].arg;
        rt_invoke(c, fn, ev[i].method == 2 ? 1 : 2, args);
    }
    for (int i = 0; i < g_nengine_cb; i++) {
        args[0] = g_engine_cb[i];
        rt_invoke(c, M32(M32(g_engine_cb[i]) + 4), 1, args);  /* OnProcessingPassEnd */
    }
    /* free voices destroyed during the pass */
    pthread_mutex_lock(&g_lock);
    for (int i = 0; i < MAX_VOICES; i++) {
        Voice *v = g_voices[i];
        if (!v || !v->dead) continue;
        for (int k = 0; k < v->qn; k++) free(v->q[(v->qhead + k) % MAX_QUEUE].decoded);
        if (v->wma) avcodec_free_context(&v->wma);
        free(v->acc);
        free(v);
        g_voices[i] = NULL;
    }
    pthread_mutex_unlock(&g_lock);
}

static uint64_t now_us(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (uint64_t)ts.tv_sec * 1000000 + (uint64_t)ts.tv_nsec / 1000;
}

static void *engine_main(void *arg) {
    (void)arg;
    CPU *c = rt_new_cpu();
    rt_thread_init(c);
    int frames = g_rate * PASS_FRAMES_MS / 1000;
    float *out = malloc(sizeof(float) * 2 * (size_t)frames);
    uint64_t next = now_us();
    g_mix_start_us = next;
    g_stat_next_us = next + 5000000;
    for (;;) {
        if (g_dev) {
            /* Keep at least four device buffers queued. SDL's OpenSL backend
             * consumes one spec.size chunk per callback and pads the rest
             * with silence when the queue is short (SDL_audio.c:569); with
             * samples=2048 that chunk is 42.7 ms, so a 40 ms queue produced a
             * dropout every callback. */
            Uint32 q0 = SDL_GetQueuedAudioSize(g_dev), q;
            uint32_t qpre = q0;
            uint32_t target = g_dev_buf_bytes ? 4 * g_dev_buf_bytes
                                              : (uint32_t)(4 * frames * 2 * sizeof(float));
            if (g_running && now_us() - g_mix_start_us > 2000000
                    && now_us() >= g_q_ignore_until) {
                if (qpre < g_qmin) g_qmin = qpre;
                if (qpre > g_qmax) g_qmax = qpre;
                if (!qpre) g_dry_passes++;
            }
            uint64_t t0 = now_us();
            while ((q = SDL_GetQueuedAudioSize(g_dev)) > target) {
                if (q < q0) { q0 = q; t0 = now_us(); }
                else if (now_us() - t0 > 200000) {
                    LOG("xaudio2: audio device stalled, mixing silently");
                    SDL_CloseAudioDevice(g_dev);
                    g_dev = 0;
                    next = now_us();
                    break;
                }
                usleep(1000);
            }
        }
        if (!g_dev) {
            uint64_t t = now_us();
            if (t < next) usleep((useconds_t)(next - t));
            next += PASS_FRAMES_MS * 1000;
        }
        if (!g_running) { usleep(PASS_FRAMES_MS * 1000); continue; }
        uint64_t t_pass = now_us();
        engine_pass(c, frames, out);
        uint64_t pass_us = now_us() - t_pass;
        if (pass_us > g_pass_window_max_us) g_pass_window_max_us = pass_us;
        if (g_dev) {
            SDL_QueueAudio(g_dev, out, (Uint32)(sizeof(float) * 2 * (size_t)frames));
            audiodump_mix(out, frames);
        }
        if (g_audiodump > 0) {
            uint64_t t = now_us();
            if (t >= g_stat_next_us) {
                LOG("xaudio2: mix qmin=%ums qmax=%ums dry=%llu passmax=%llums silence=%llums",
                    g_qmin == ~0u ? 0u : g_qmin / g_dev_bytes_per_ms,
                    g_qmax / g_dev_bytes_per_ms,
                    g_dry_passes, g_pass_window_max_us / 1000,
                    g_silence_frames * 1000 / (unsigned long long)g_rate);
                g_qmin = ~0u;
                g_qmax = 0;
                g_dry_passes = 0;
                g_pass_window_max_us = 0;
                g_q_ignore_until = t + 300000;
                g_stat_next_us = t + 5000000;
            }
        }
    }
    return NULL;
}

static void engine_start_thread(void) {
    if (g_thread_started) return;
    g_thread_started = 1;
    /* The 0.30 runtime uses OpenSL ES at 48 kHz (AAudio adds latency). An
     * empty save/stockaudio.txt restores SDL's default backend. */
    {
        extern char g_save_dir[];
        char path[1100];
        snprintf(path, sizeof path, "%s/stockaudio.txt", g_save_dir);
        if (access(path, F_OK) == 0) {
            LOG("audio: SDL default backend (stockaudio override)");
        } else {
            SDL_SetHint(SDL_HINT_AUDIODRIVER, "openslES");
            LOG("audio: openslES @ %d Hz (save/stockaudio.txt to restore SDL default)", g_rate);
        }
    }
    if (!SDL_WasInit(SDL_INIT_AUDIO)) SDL_InitSubSystem(SDL_INIT_AUDIO);
    SDL_AudioSpec want = {0}, have;
    want.freq = g_rate;
    want.format = AUDIO_F32SYS;
    want.channels = 2;
    want.samples = 1024;
    rt_host_affinity_begin();  /* SDL's audio thread runs no guest code */
    g_dev = SDL_OpenAudioDevice(NULL, 0, &want, &have, 0);
    rt_host_affinity_end();
    if (!g_dev) LOG("xaudio2: SDL_OpenAudioDevice failed: %s (mixing silently)", SDL_GetError());
    else {
        g_dev_buf_bytes = (uint32_t)(SDL_AUDIO_BITSIZE(have.format) / 8
                                     * have.channels * have.samples);
        if (have.samples)
            g_dev_bytes_per_ms = (uint32_t)((uint64_t)g_dev_buf_bytes
                                            * have.freq / have.samples / 1000);
        LOG("xaudio2: device %d Hz, %d ch, %d samples, %d bits%s (%u B/buffer, %u B/ms)",
            have.freq, have.channels, have.samples, SDL_AUDIO_BITSIZE(have.format),
            SDL_AUDIO_ISFLOAT(have.format) ? " float" : "", g_dev_buf_bytes, g_dev_bytes_per_ms);
        SDL_PauseAudioDevice(g_dev, 0);
    }
    audiodump_begin();
    pthread_t t;
    pthread_create(&t, NULL, engine_main, NULL);
    pthread_detach(t);
    static int decode_started;
    if (!decode_started) {
        decode_started = 1;
        pthread_t d;
        pthread_create(&d, NULL, wma_decode_main, (void *)0L);   /* short buffers */
        pthread_detach(d);
        pthread_create(&d, NULL, wma_decode_main, (void *)1L);   /* long buffers */
        pthread_detach(d);
    }
}

/* ---------------------------------------------------------------- voices */
static Voice *voice_new(int kind, int ch, int rate, uint32_t flags) {
    Voice *v = calloc(1, sizeof *v);
    v->kind = kind;
    v->in_ch = ch < 1 ? 2 : ch > MAX_CH ? MAX_CH : ch;
    v->rate = rate ? rate : g_rate;
    v->flags = flags;
    v->volume = 1.0f;
    v->ratio = 1.0f;
    for (int i = 0; i < MAX_CH; i++) v->chvol[i] = 1.0f;
    if (kind != V_SOURCE) v->acc = calloc((size_t)(v->in_ch * 48000 / 10 * 2), sizeof(float));
    pthread_mutex_lock(&g_lock);
    for (int i = 0; i < MAX_VOICES; i++)
        if (!g_voices[i]) { g_voices[i] = v; break; }
    pthread_mutex_unlock(&g_lock);
    return v;
}

static void set_sends(Voice *v, uint32_t sl) {
    v->nsends = 0;
    memset(v->matrix_set, 0, sizeof v->matrix_set);
    if (!sl) return;
    uint32_t n = M32(sl), p = M32(sl + 4);
    for (uint32_t i = 0; i < n && i < MAX_SENDS; i++) {
        Voice *d = hcom_host(M32(p + 8 * i + 4));
        v->send[v->nsends++] = d;
    }
}

#define V ((Voice *)hcom_host(ARG(0)))
SHIM(xv_GetVoiceDetails) {
    Voice *v = V;
    M32(ARG(1)) = v->flags;
    M32(ARG(1) + 4) = (uint32_t)v->in_ch;
    M32(ARG(1) + 8) = (uint32_t)v->rate;
    RET(0, 2);
}
SHIM(xv_SetOutputVoices) {
    pthread_mutex_lock(&g_lock);
    set_sends(V, ARG(1));
    pthread_mutex_unlock(&g_lock);
    RET(0, 2);
}
SHIM(xv_SetEffectChain) { RET(0, 2); }
SHIM(xv_EnableEffect) { RET(0, 3); }
SHIM(xv_DisableEffect) { RET(0, 3); }
SHIM(xv_GetEffectState) { if (ARG(2)) M32(ARG(2)) = 1; RET(0, 3); }
SHIM(xv_SetEffectParameters) { RET(0, 5); }
SHIM(xv_GetEffectParameters) { if (ARG(2)) memset(MEM + ARG(2), 0, ARG(3)); RET(0, 4); }
SHIM(xv_SetFilterParameters) { RET(0, 3); }
SHIM(xv_GetFilterParameters) {
    /* LowPassFilter, frequency 1.0 (passes everything), 1/Q 1.0 */
    M32(ARG(1)) = 0;
    MF32(ARG(1) + 4) = 1.0f;
    MF32(ARG(1) + 8) = 1.0f;
    RET(0, 2);
}
SHIM(xv_SetOutputFilterParameters) { RET(0, 4); }
SHIM(xv_GetOutputFilterParameters) {
    M32(ARG(2)) = 0;
    MF32(ARG(2) + 4) = 1.0f;
    MF32(ARG(2) + 8) = 1.0f;
    RET(0, 3);
}
SHIM(xv_SetVolume) { V->volume = ARGF(1); RET(0, 3); }
SHIM(xv_GetVolume) { MF32(ARG(1)) = V->volume; RET(0, 2); }
SHIM(xv_SetChannelVolumes) {
    Voice *v = V;
    for (uint32_t i = 0; i < ARG(1) && i < MAX_CH; i++) v->chvol[i] = MF32(ARG(2) + 4 * i);
    RET(0, 4);
}
SHIM(xv_GetChannelVolumes) {
    Voice *v = V;
    for (uint32_t i = 0; i < ARG(1) && i < MAX_CH; i++) MF32(ARG(2) + 4 * i) = v->chvol[i];
    RET(0, 3);
}
SHIM(xv_SetOutputMatrix) {
    Voice *v = V, *d = hcom_host(ARG(1));
    uint32_t sc = ARG(2), dc = ARG(3), m = ARG(4);
    pthread_mutex_lock(&g_lock);
    int idx = -1;
    for (int i = 0; i < v->nsends; i++) if (v->send[i] == d) idx = i;
    if (idx < 0 && !v->nsends && v->kind != V_MASTER) {  /* default send to the master */
        v->send[0] = g_master;
        v->nsends = 1;
        idx = d == g_master || !d ? 0 : -1;
    }
    if (idx >= 0 && sc <= MAX_CH && dc <= MAX_CH) {
        memset(v->matrix[idx], 0, sizeof v->matrix[idx]);
        for (uint32_t k = 0; k < sc * dc; k++) v->matrix[idx][k] = MF32(m + 4 * k);
        v->matrix_set[idx] = 1;
    }
    pthread_mutex_unlock(&g_lock);
    RET(0, 6);
}
SHIM(xv_GetOutputMatrix) {
    Voice *v = V, *d = hcom_host(ARG(1));
    uint32_t sc = ARG(2), dc = ARG(3), m = ARG(4);
    float def[MAX_CH * MAX_CH];
    const float *src = def;
    int idx = -1;
    for (int i = 0; i < v->nsends; i++) if (v->send[i] == d) idx = i;
    if (idx >= 0 && v->matrix_set[idx]) src = v->matrix[idx];
    else default_matrix((int)sc, (int)dc, def);
    for (uint32_t k = 0; k < sc * dc && k < MAX_CH * MAX_CH; k++) MF32(m + 4 * k) = src[k];
    RET(0, 5);
}
SHIM(xv_DestroyVoice) {
    Voice *v = V;
    uint32_t g = ARG(0);
    pthread_mutex_lock(&g_lock);
    v->dead = 1;
    if (v == g_master) g_master = NULL;
    for (int i = 0; i < MAX_VOICES; i++) {
        Voice *o = g_voices[i];
        if (o) for (int k = 0; k < o->nsends; k++) if (o->send[k] == v) o->send[k] = NULL;
    }
    pthread_mutex_unlock(&g_lock);
    g_hcom_slots[M32(g + 8)] = NULL;
    M32(g) = 0xDEAD0002u;
    gfree(g);
    if (!g_running) {  /* no engine pass will free it */
        pthread_mutex_lock(&g_lock);
        for (int i = 0; i < MAX_VOICES; i++)
            if (g_voices[i] == v) {
                if (v->wma) avcodec_free_context(&v->wma);
                free(v->acc); free(v); g_voices[i] = NULL;
            }
        pthread_mutex_unlock(&g_lock);
    }
    RET(0, 1);
}
SHIM(xs_Start) { V->running = 1; RET(0, 3); }
SHIM(xs_Stop) { V->running = 0; RET(0, 3); }
SHIM(xs_SubmitSourceBuffer) {
    Voice *v = V;
    uint32_t b = ARG(1);
    pthread_mutex_lock(&g_lock);
    if (v->qn >= MAX_QUEUE) { pthread_mutex_unlock(&g_lock); RET(0x88960001u, 3); } /* XAUDIO2_E_INVALID_CALL */
    XBuf *x = &v->q[(v->qhead + v->qn) % MAX_QUEUE];
    memset(x, 0, sizeof *x);
    x->flags = M32(b);
    x->bytes = M32(b + 4);
    x->data = M32(b + 8);
    x->play_begin = M32(b + 12);
    x->play_len = M32(b + 16);
    x->loop_begin = M32(b + 20);
    x->loop_len = M32(b + 24);
    x->loop_count = M32(b + 28);
    x->ctx = M32(b + 32);
    if (v->fmt == FMT_WMA) {
        static unsigned s_wma_id;
        x->wma_off = 0;
        x->wma_done = 0;
        x->decoding = 0;
        x->pcm_n = x->pcm_rd = x->pcm_cap = 0;
        x->decoded = NULL;
        x->id = ++s_wma_id;
        x->nframes = 0;
        x->pos = 0;
        x->end = 0;
        if (x->id <= 3)
            LOG("xaudio2: xWMA stream %u: %u bytes (%d ch, %d Hz, block %d)",
                x->id, x->bytes, v->in_ch, v->rate, v->block_align);
        /* The decode worker picks this up and turns the whole compressed
         * buffer into PCM; the mixer waits for it (silence before the first
         * frame) instead of ever decoding or padding mid-stream. */
    } else if (v->fmt == FMT_ADPCM && v->block_align && v->spb) {
        uint32_t nb = x->bytes / (uint32_t)v->block_align;
        x->nframes = nb * (uint32_t)v->spb;
        x->decoded = malloc(sizeof(int16_t) * (size_t)x->nframes * (size_t)v->in_ch + 2);
        for (uint32_t i = 0; i < nb; i++)
            adpcm_block(v, MEM + x->data + i * (uint32_t)v->block_align, x->decoded + (size_t)i * (size_t)v->spb * (size_t)v->in_ch);
    } else if (v->fmt == FMT_NONE) {
        x->nframes = 0;
    } else {
        x->nframes = v->bpf ? x->bytes / (uint32_t)v->bpf : 0;
    }
    if (v->fmt != FMT_WMA) {
        x->pos = x->play_begin < x->nframes ? x->play_begin : x->nframes;
        x->end = x->play_len ? x->play_begin + x->play_len : x->nframes;
        if (x->end > x->nframes) x->end = x->nframes;
        if (!x->loop_len && x->loop_count) x->loop_len = x->end - x->loop_begin;
    }
    x->loops_left = x->loop_count == 255 ? 255 : (int)x->loop_count;
    v->qn++;
    pthread_mutex_unlock(&g_lock);
    RET(0, 3);
}
SHIM(xs_FlushSourceBuffers) {
    Voice *v = V;
    pthread_mutex_lock(&g_lock);
    if (v->fmt == FMT_WMA) {
        /* flushing stops every xWMA sound: mark all buffers finished and
         * reset the decoder for whatever the game submits next */
        for (int k = 0; k < v->qn; k++) {
            XBuf *b = &v->q[(v->qhead + k) % MAX_QUEUE];
            b->wma_done = 1;
            if (b->decoding == 1) b->decoding = 2;  /* drop the in-flight result */
            b->pcm_n = b->pcm_rd = 0;
            b->pos = b->end;
            b->loops_left = 0;
            b->flags &= ~0x40u;
        }
        if (v->wma) avcodec_flush_buffers(v->wma);
    } else {
        /* every queued buffer but the playing one ends, reporting OnBufferEnd
         * on the next pass: keep the current one, mark the rest finished */
        for (int k = v->running ? 1 : 0; k < v->qn; k++) {
            XBuf *b = &v->q[(v->qhead + k) % MAX_QUEUE];
            b->pos = b->end;
            b->loops_left = 0;
            b->flags &= ~0x40u;
        }
    }
    if (!v->running && v->qn && !g_running) {  /* stopped engine: drop at once */
        for (int k = 0; k < v->qn; k++) free(v->q[(v->qhead + k) % MAX_QUEUE].decoded);
        v->qn = 0;
    }
    pthread_mutex_unlock(&g_lock);
    RET(0, 1);
}
SHIM(xs_Discontinuity) { RET(0, 1); }
SHIM(xs_ExitLoop) {
    Voice *v = V;
    pthread_mutex_lock(&g_lock);
    if (v->qn) v->q[v->qhead].loops_left = 0;
    pthread_mutex_unlock(&g_lock);
    RET(0, 2);
}
SHIM(xs_GetState) {
    Voice *v = V;
    uint32_t s = ARG(1);
    pthread_mutex_lock(&g_lock);
    M32(s) = v->qn ? v->q[v->qhead].ctx : 0;
    M32(s + 4) = (uint32_t)v->qn;
    M32(s + 8) = (uint32_t)v->played;
    M32(s + 12) = (uint32_t)(v->played >> 32);
    pthread_mutex_unlock(&g_lock);
    RET(0, 2);
}
SHIM(xs_SetFrequencyRatio) {
    float r = ARGF(1);
    Voice *v = V;
    if (r < 1.0f / 1024) r = 1.0f / 1024;
    if (v->max_ratio > 0 && r > v->max_ratio) r = v->max_ratio;
    v->ratio = r;
    RET(0, 3);
}
SHIM(xs_GetFrequencyRatio) { MF32(ARG(1)) = V->ratio; RET(0, 2); }
SHIM(xs_SetSourceSampleRate) { V->rate = (int)ARG(1); RET(0, 2); }
#undef V

#define VOICE_FNS \
    shim_xv_GetVoiceDetails, shim_xv_SetOutputVoices, shim_xv_SetEffectChain, shim_xv_EnableEffect, \
    shim_xv_DisableEffect, shim_xv_GetEffectState, shim_xv_SetEffectParameters, shim_xv_GetEffectParameters, \
    shim_xv_SetFilterParameters, shim_xv_GetFilterParameters, shim_xv_SetOutputFilterParameters, \
    shim_xv_GetOutputFilterParameters, shim_xv_SetVolume, shim_xv_GetVolume, \
    shim_xv_SetChannelVolumes, shim_xv_GetChannelVolumes, shim_xv_SetOutputMatrix, shim_xv_GetOutputMatrix, \
    shim_xv_DestroyVoice
static const ShimFn k_src_fns[] = {
    VOICE_FNS, shim_xs_Start, shim_xs_Stop, shim_xs_SubmitSourceBuffer, shim_xs_FlushSourceBuffers,
    shim_xs_Discontinuity, shim_xs_ExitLoop, shim_xs_GetState, shim_xs_SetFrequencyRatio,
    shim_xs_GetFrequencyRatio, shim_xs_SetSourceSampleRate,
};
static const ShimFn k_sub_fns[] = {VOICE_FNS};

/* ---------------------------------------------------------------- IXAudio2 */
SHIM(xa_QueryInterface) {
    hcom_addref(ARG(0));
    M32(ARG(2)) = ARG(0);
    RET(0, 3);
}
SHIM(xa_AddRef) { RET(hcom_addref(ARG(0)), 1); }
SHIM(xa_Release) {
    uint32_t left;
    if (hcom_release(ARG(0), &left)) g_running = 0;
    RET(left, 1);
}
SHIM(xa_GetDeviceCount) { M32(ARG(1)) = 1; RET(0, 2); }
SHIM(xa_GetDeviceDetails) {
    uint32_t d = ARG(2);
    memset(MEM + d, 0, 1068);
    const char *id = "SDL", *name = "Speakers";
    for (int i = 0; id[i]; i++) M16(d + 2 * i) = (uint8_t)id[i];
    for (int i = 0; name[i]; i++) M16(d + 512 + 2 * i) = (uint8_t)name[i];
    M32(d + 1024) = 0xF;  /* GlobalDefaultDevice */
    uint32_t f = d + 1028;
    M16(f) = 0xFFFE;
    M16(f + 2) = 2;
    M32(f + 4) = (uint32_t)g_rate;
    M32(f + 8) = (uint32_t)g_rate * 8;
    M16(f + 12) = 8;
    M16(f + 14) = 32;
    M16(f + 16) = 22;
    M16(f + 18) = 32;
    M32(f + 20) = 3;  /* SPEAKER_FRONT_LEFT | SPEAKER_FRONT_RIGHT */
    static const uint8_t k_float_guid[16] = {3, 0, 0, 0, 0, 0, 0x10, 0, 0x80, 0, 0, 0xaa, 0, 0x38, 0x9b, 0x71};
    memcpy(MEM + f + 24, k_float_guid, 16);
    RET(0, 3);
}
SHIM(xa_Initialize) { RET(0, 3); }
SHIM(xa_RegisterForCallbacks) {
    if (g_nengine_cb < 16) g_engine_cb[g_nengine_cb++] = ARG(1);
    RET(0, 2);
}
SHIM(xa_UnregisterForCallbacks) {
    for (int i = 0; i < g_nengine_cb; i++)
        if (g_engine_cb[i] == ARG(1)) { g_engine_cb[i] = g_engine_cb[--g_nengine_cb]; break; }
    RET(0, 2);
}
/* CreateSourceVoice(this, ppVoice, pFormat, Flags, MaxFrequencyRatio, pCallback, pSendList, pEffectChain) */
SHIM(xa_CreateSourceVoice) {
    uint32_t wf = ARG(2);
    Voice *v = voice_new(V_SOURCE, M16(wf + 2), (int)M32(wf + 4), ARG(3));
    pthread_mutex_lock(&g_lock);
    set_format(v, wf);
    v->max_ratio = ARGF(4);
    v->callback = ARG(5);
    set_sends(v, ARG(6));
    pthread_mutex_unlock(&g_lock);
    v->guest = hcom_new(&k_src, v);
    M32(ARG(1)) = v->guest;
    TRACE("xaudio2: source voice %d ch %d Hz fmt %d cb %08x", v->in_ch, v->rate, v->fmt, v->callback);
    RET(0, 8);
}
/* CreateSubmixVoice(this, ppVoice, InputChannels, InputSampleRate, Flags, ProcessingStage, pSendList, pEffectChain) */
SHIM(xa_CreateSubmixVoice) {
    Voice *v = voice_new(V_SUBMIX, (int)ARG(2), (int)ARG(3), ARG(4));
    pthread_mutex_lock(&g_lock);
    v->stage = (int)(ARG(5) < 64 ? ARG(5) : 63);
    set_sends(v, ARG(6));
    pthread_mutex_unlock(&g_lock);
    v->guest = hcom_new(&k_sub, v);
    M32(ARG(1)) = v->guest;
    RET(0, 8);
}
/* CreateMasteringVoice(this, ppVoice, InputChannels, InputSampleRate, Flags, DeviceIndex, pEffectChain) */
SHIM(xa_CreateMasteringVoice) {
    int ch = ARG(2) ? (int)ARG(2) : 2;
    if (ARG(3)) g_rate = (int)ARG(3);
    Voice *v = voice_new(V_MASTER, ch, g_rate, ARG(4));
    g_master = v;
    v->guest = hcom_new(&k_mst, v);
    M32(ARG(1)) = v->guest;
    LOG("xaudio2: mastering voice %d ch at %d Hz", v->in_ch, g_rate);
    engine_start_thread();  /* real XAudio2 processes from creation; StartEngine is optional */
    RET(0, 7);
}
SHIM(xa_StartEngine) { g_running = 1; engine_start_thread(); RET(0, 1); }
SHIM(xa_StopEngine) { g_running = 0; RET(0, 1); }
SHIM(xa_CommitChanges) { RET(0, 2); }
SHIM(xa_GetPerformanceData) { memset(MEM + ARG(1), 0, 64); RET(0, 2); }
SHIM(xa_SetDebugConfiguration) { RET(0, 3); }

static const ShimFn k_xa_fns[] = {
    shim_xa_QueryInterface, shim_xa_AddRef, shim_xa_Release, shim_xa_GetDeviceCount, shim_xa_GetDeviceDetails,
    shim_xa_Initialize, shim_xa_RegisterForCallbacks, shim_xa_UnregisterForCallbacks, shim_xa_CreateSourceVoice,
    shim_xa_CreateSubmixVoice, shim_xa_CreateMasteringVoice, shim_xa_StartEngine, shim_xa_StopEngine,
    shim_xa_CommitChanges, shim_xa_GetPerformanceData, shim_xa_SetDebugConfiguration,
};

/* XAPOFX effects: plain IUnknown objects that the voices never run. */
SHIM(fx_QueryInterface) { hcom_addref(ARG(0)); M32(ARG(2)) = ARG(0); RET(0, 3); }
SHIM(fx_AddRef) { RET(hcom_addref(ARG(0)), 1); }
SHIM(fx_Release) { uint32_t left; hcom_release(ARG(0), &left); RET(left, 1); }
SHIM(fx_Stub) { RET(0, 1); }
static const ShimFn k_fx_fns[] = {shim_fx_QueryInterface, shim_fx_AddRef, shim_fx_Release,
                                  shim_fx_Stub, shim_fx_Stub, shim_fx_Stub, shim_fx_Stub, shim_fx_Stub};

static void classes_init(void) {
    static int done;
    if (done) return;
    done = 1;
    hcom_class_init(&k_xa, "IXAudio2", k_xa_fns, sizeof k_xa_fns / sizeof k_xa_fns[0]);
    hcom_class_init(&k_src, "IXAudio2SourceVoice", k_src_fns, sizeof k_src_fns / sizeof k_src_fns[0]);
    hcom_class_init(&k_sub, "IXAudio2SubmixVoice", k_sub_fns, sizeof k_sub_fns / sizeof k_sub_fns[0]);
    hcom_class_init(&k_mst, "IXAudio2MasteringVoice", k_sub_fns, sizeof k_sub_fns / sizeof k_sub_fns[0]);
    hcom_class_init(&k_fx, "XAPOFX", k_fx_fns, sizeof k_fx_fns / sizeof k_fx_fns[0]);
}

/* Called by CoCreateInstance: 1 and *out set when clsid is XAudio2's. */
int xaudio2_create(uint32_t clsid, uint32_t *out) {
    if (!guid_eq(clsid, CLSID_XAudio2) && !guid_eq(clsid, CLSID_XAudio2_Debug) &&
        !guid_eq(clsid, CLSID_XAudio2_26) && !guid_eq(clsid, CLSID_XAudio2_26_Debug)) return 0;
    classes_init();
    *out = hcom_new(&k_xa, NULL);
    g_running = 1;
    LOG("xaudio2: created IXAudio2 %08x", *out);
    return 1;
}

/* XAPOFX1_5!CreateFX(REFCLSID, IUnknown **, const void *init, UINT32 size), cdecl */
SHIM(CreateFX) {
    classes_init();
    M32(ARG(1)) = hcom_new(&k_fx, NULL);
    RETC(0);
}

static const ShimDef defs[] = {
    {"XAPOFX1_5.dll", "CreateFX", shim_CreateFX},
    {NULL, NULL, NULL}
};

void xaudio2_init(void) {
    pthread_mutexattr_t a;
    pthread_mutexattr_init(&a);
    pthread_mutexattr_settype(&a, PTHREAD_MUTEX_RECURSIVE);
    pthread_mutex_init(&g_lock, &a);
    rt_register_shims(defs);
}
