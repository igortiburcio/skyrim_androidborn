// Watches the face tints (FaceGen) the engine *builds*: logs what it writes,
// dumps a level to <save>/texdump/*.dds for inspection, and - by default -
// keeps the last good colours when a rebuild comes out empty.
//
// Switches (files in the port's save folder, like the other port options):
//   facetrace.txt  log and dump the face tints the game builds
//   nofacefix.txt  turn the repair off (it is on by default)
//
// Any 1:2 block-compressed map with a full mip chain is watched: the game
// builds the player's face tint that way, and so does every actor whose face
// data has to be generated. Ordinary art can match the shape, which is why the
// log and the dump are kept to one sample per size and blanks.
#include "rt.h"
#include "tex_watch.h"

#include <atomic>
#include <map>
#include <mutex>
#include <vector>

#include <sys/stat.h>
#include <unistd.h>

namespace {

struct Watch {
    uint32_t w = 0, h = 0, fmt = 0;
    uint32_t writes = 0;         // full-level writes seen
    uint64_t last = 0;           // checksum of the newest write
    std::vector<uint8_t> good;   // newest non-blank level 0
};

std::mutex g_lock;
std::map<void *, Watch> g_watched;
std::atomic<int> g_nwatched{0};
size_t g_good_bytes, g_dump_bytes;
int g_trace = -1, g_fix = -1, g_dumps;

// Snapshot bytes kept for the repair; enough for a few faces, never for the
// whole game's heads.
constexpr size_t kGoodCap = 8u << 20;
// Dumps are bounded by size as well as count.
constexpr size_t kDumpBytes = 16u << 20;
constexpr int kMaxDumps = 32;

bool switch_on(const char *name) {
    char path[1100];
    snprintf(path, sizeof path, "%s/%s", g_save_dir, name);
    return access(path, F_OK) == 0;
}

void read_switches() {
    static std::once_flag once;
    std::call_once(once, [] {
        bool facefix = switch_on("facefix.txt"), nofacefix = switch_on("nofacefix.txt");
        g_trace = switch_on("facetrace.txt") ? 1 : 0;
        g_fix = texwatch::repair_enabled(facefix, nofacefix) ? 1 : 0;
        if (g_trace) LOG("facetrace: dumping face tints to %s/texdump", g_save_dir);
        else if (g_fix) LOG("facefix: keeping the last good face tint (nofacefix.txt turns it off)");
    });
}

bool trace_on() {
    read_switches();
    return g_trace != 0;
}

bool fix_on() {
    read_switches();
    return g_fix != 0;
}

/* One dump per shape: the first write of each (format, width, height). A city
 * load builds dozens of faces; a report only needs one sample of each size. */
std::map<uint64_t, bool> g_sampled;

bool sample(uint32_t fmt, uint32_t w, uint32_t h) {
    uint64_t key = (uint64_t)fmt << 40 | (uint64_t)w << 20 | h;
    return g_sampled.emplace(key, true).second;
}

const char *dds_name(uint32_t fmt) {
    if (fmt == bc::kDxt1) return "DXT1";
    if (fmt == bc::kDxt3 || fmt == bc::kDxt2) return "DXT3";
    return "DXT5";
}

void dump(const Watch &w, const uint8_t *data, size_t size, uint64_t sum) {
    if (g_dumps >= kMaxDumps || g_dump_bytes + size > kDumpBytes) return;
    char dir[1100], path[1200];
    snprintf(dir, sizeof dir, "%s/texdump", g_save_dir);
    mkdir(dir, 0755);
    snprintf(path, sizeof path, "%s/tint_%d_%ux%u_%016llx.dds", dir, ++g_dumps, w.w, w.h,
             (unsigned long long)sum);
    FILE *f = fopen(path, "wb");
    if (!f) return;
    uint8_t hdr[128];
    texwatch::dds_header(hdr, w.w, w.h, w.fmt, size);
    int ok = fwrite(hdr, 1, sizeof hdr, f) == sizeof hdr && fwrite(data, 1, size, f) == size;
    fclose(f);
    if (!ok) return;
    g_dump_bytes += size;
    LOG("facetrace: wrote %s (%ux%u %s)", path, w.w, w.h, dds_name(w.fmt));
}

} // namespace

void tex_watch_create(void *tex, uint32_t w, uint32_t h, uint32_t fmt, uint32_t pool, uint32_t levels) {
    if (!tex || !texwatch::is_face_tint(w, h, fmt, pool, levels)) return;
    if (!trace_on() && !fix_on()) return;
    {
        std::lock_guard<std::mutex> l(g_lock);
        auto it = g_watched.find(tex);
        if (it != g_watched.end()) {
            // The host address came back for a new texture: a stale snapshot
            // would repaint this face with the previous one's colours.
            g_good_bytes -= it->second.good.size();
            it->second = Watch{};
        } else {
            it = g_watched.emplace(tex, Watch{}).first;
        }
        it->second.w = w;
        it->second.h = h;
        it->second.fmt = fmt;
        g_nwatched.store((int)g_watched.size(), std::memory_order_relaxed);
    }
    if (g_trace) LOG("facetrace: face tint %p created %ux%u %s levels %u", tex, w, h, dds_name(fmt),
                     levels);
}

void tex_watch_unlock(void *tex, uint32_t sub, uint8_t *data, size_t size) {
    if (!tex || sub != 0 || !data) return;
    /* This runs on every texture, surface and buffer unlock the game makes, so
     * the switches are checked before anything else: with both off the whole
     * watch costs two integer reads. */
    if (!trace_on() && !fix_on()) return;
    if (g_nwatched.load(std::memory_order_relaxed) == 0) return;  // nothing to look at
    std::lock_guard<std::mutex> l(g_lock);
    auto it = g_watched.find(tex);
    if (it == g_watched.end()) return;
    Watch &w = it->second;
    if (size != texwatch::level_size(w.w, w.h, w.fmt)) return;  // a partial lock
    w.writes++;
    uint64_t sum = texwatch::checksum(data, size);
    if (sum == w.last) return;  // the same content again
    w.last = sum;
    bool blank = texwatch::is_blank(data, size);
    if (g_trace) {
        LOG("facetrace: face tint %p write %u, %s, checksum %016llx%s", tex, w.writes,
            blank ? "blank" : "content", (unsigned long long)sum,
            (!blank && !w.good.empty()) ? " (replaces colours)" : "");
        /* One sample of each size is enough for a report; blanks always go in. */
        if (blank || sample(w.fmt, w.w, w.h)) dump(w, data, size, sum);
    }
    if (!blank) {
        w.good.assign(data, data + size);
        g_good_bytes += size;
        if (g_good_bytes > kGoodCap) {
            // Too many faces alive: keep only this one's colours.
            for (auto &kv : g_watched)
                if (&kv.second != &w && !kv.second.good.empty()) {
                    g_good_bytes -= kv.second.good.size();
                    kv.second.good.clear();
                    kv.second.good.shrink_to_fit();
                }
        }
        return;
    }
    if (!fix_on()) return;
    if (w.good.empty()) {
        LOG("facefix: face tint %p came out blank and there is no earlier copy", tex);
        return;
    }
    memcpy(data, w.good.data(), size);
    LOG("facefix: face tint %p came out blank; kept the previous colours (%s)", tex,
        dds_name(w.fmt));
}

void tex_watch_forget(void *tex) {
    std::lock_guard<std::mutex> l(g_lock);
    auto it = g_watched.find(tex);
    if (it == g_watched.end()) return;
    g_good_bytes -= it->second.good.size();
    g_watched.erase(it);
    g_nwatched.store((int)g_watched.size(), std::memory_order_relaxed);
}

// Cheap check for the lock path: no face tint has been created yet.
bool tex_watch_active(void) {
    return g_nwatched.load(std::memory_order_relaxed) != 0;
}
