// Failure-injection checks for FileUtils::replaceDirAtomically.
#include <cstdio>
#include <cstdlib>
#include <string>
#include <sys/stat.h>
#include <unistd.h>
#include "dictionary/utils/file_utils.h"
using namespace latinime;
static std::string base;
static void mk(const std::string &d, const char *content) { mkdir(d.c_str(), 0700); FILE *f = fopen((d + "/f").c_str(), "w"); fputs(content, f); fclose(f); }
static std::string rd(const std::string &d) { FILE *f = fopen((d + "/f").c_str(), "r"); if (!f) return "<missing>"; char b[64] = {0}; fgets(b, 63, f); fclose(f); return b; }
static bool exists(const std::string &p) { struct stat s; return stat(p.c_str(), &s) == 0; }
static int fails = 0;
static void expect(const char *n, bool c) { printf("%s %s\n", c ? "PASS" : "FAIL", n); if (!c) fails++; }
int main() {
    char t[] = "/tmp/rdXXXXXX"; base = mkdtemp(t);
    std::string live = base + "/dict", tmp = base + "/dict.tmp", bak = base + "/dict.bak";
    mk(live, "OLD"); mk(tmp, "NEW");
    expect("replace succeeds", FileUtils::replaceDirAtomically(tmp.c_str(), live.c_str()));
    expect("live has new content", rd(live) == "NEW");
    expect("backup removed", !exists(bak));
    expect("temp consumed", !exists(tmp));
    // failure injection: the new directory is missing, so the second rename fails
    mk(live, "KEEP"); // dir exists already; overwrite file content
    { FILE *f = fopen((live + "/f").c_str(), "w"); fputs("KEEP", f); fclose(f); }
    expect("replace with missing new dir fails", !FileUtils::replaceDirAtomically((base + "/nonexistent").c_str(), live.c_str()));
    expect("old dictionary restored after failed swap", rd(live) == "KEEP");
    expect("no stray backup after rollback", !exists(bak));
    // stale backup from an earlier interrupted run is cleared, not trusted
    mk(bak, "STALE"); mk(tmp, "NEW2");
    expect("replace over stale backup succeeds", FileUtils::replaceDirAtomically(tmp.c_str(), live.c_str()));
    expect("live has NEW2, stale backup gone", rd(live) == "NEW2" && !exists(bak));
    // first ever flush: no live dictionary yet
    FileUtils::removeDirAndFiles(live.c_str()); rmdir(live.c_str());
    mk(tmp, "FIRST");
    expect("first write without an old dictionary", FileUtils::replaceDirAtomically(tmp.c_str(), live.c_str()) && rd(live) == "FIRST");
    printf("%s: %d failing\n", fails ? "RESULT FAIL" : "RESULT OK", fails);
    return fails ? 1 : 0;
}
