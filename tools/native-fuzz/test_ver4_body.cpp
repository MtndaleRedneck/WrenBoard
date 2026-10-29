// Host regression test for the Ver4DictBuffers body length-prefix walk
// (ver4_dict_buffers.cpp). A crafted backup restore reaches this open path
// after the swap. The declared buffer size must be validated against the
// remaining bytes BEFORE subView/position advance: subView's bounds check is
// ASSERT-only (compiled out under NDEBUG) and position += bufferSize would be
// signed-overflow UB for large values.
#include <cstdio>
#include <cstdint>
#include <cstring>
#include <string>
#include <vector>
#include <sys/stat.h>
#include <unistd.h>
#include "dictionary/structure/v4/ver4_dict_buffers.h"
#include "dictionary/utils/mmapped_buffer.h"
using namespace latinime;
typedef std::vector<uint8_t> B;
static int g_fail = 0;
static void u32be(B &b, uint32_t v){ for(int i=3;i>=0;i--) b.push_back((v>>(8*i))&0xff); }
static B header(){ B b; u32be(b,0x9BC13AFE); b.push_back(0x01); b.push_back(0x92); b.push_back(0); b.push_back(0); u32be(b,12); return b; } // v402, no attrs
static void wr(const std::string &p, const B &b){ FILE *f=fopen(p.c_str(),"wb"); fwrite(b.data(),1,b.size(),f); fclose(f); }
static bool try_open(const B &body){
    system("rm -rf /tmp/v4b_case && mkdir -p /tmp/v4b_case");
    const char *dp = "/tmp/v4b_case/userdict";
    wr(std::string(dp)+".header", header());
    wr(std::string(dp)+".body", body);
    auto hb = MmappedBuffer::openBuffer(dp, Ver4DictConstants::HEADER_FILE_EXTENSION, false);
    if(!hb){ printf("SKIP: header mmap failed\n"); g_fail++; return false; }
    auto p = Ver4DictBuffers::openVer4DictBuffers(dp, std::move(hb), FormatUtils::VERSION_402);
    return p != nullptr;
}
static void expect(const char *name, bool got, bool want){
    if(got == want) printf("PASS %s: %s (expected %s)\n", name, got?"accepted":"rejected", want?"accept":"reject");
    else { printf("FAIL %s: %s (expected %s)\n", name, got?"accepted":"rejected", want?"accept":"reject"); g_fail++; }
}
static B bodyFromLens(const std::vector<uint32_t> &lens, uint8_t fill){
    B b; for(uint32_t L : lens){ u32be(b,L); for(uint32_t i=0;i<L && i<(1u<<22);i++) b.push_back(fill); } return b;
}
int main(){
    setvbuf(stdout,nullptr,_IONBF,0);
    // control: 7 empty buffers (NUM_OF_CONTENT_BUFFERS_IN_BODY_FILE = 7)
    expect("control: 7 empty buffers", try_open(bodyFromLens({0,0,0,0,0,0,0}, 0)), true);
    // control: 7 buffers with content
    expect("control: 7 buffers with content", try_open(bodyFromLens({64,32,48,24,16,40,56}, 0x00)), true);
    // declared size 0x7FFFFFFF: old code overflowed position += bufferSize (UBSan)
    { B b; u32be(b, 0x7FFFFFFFu); expect("single length 0x7FFFFFFF", try_open(b), false); }
    // declared size 0xFFFFFFFF: negative as int
    { B b; u32be(b, 0xFFFFFFFFu); expect("single length 0xFFFFFFFF", try_open(b), false); }
    // two lengths each fitting nowhere near the file, summing past INT_MAX
    { B b; u32be(b, 0x40000000u); u32be(b, 0x40000000u); expect("two lengths summing past INT_MAX", try_open(b), false); }
    // length field itself truncated
    { B b = {0x00, 0x00}; expect("truncated length field", try_open(b), false); }
    // declared size larger than remaining bytes (small overclaim)
    { B b; u32be(b, 100u); b.push_back(0x41); expect("overclaim larger than remaining", try_open(b), false); }
    // wrong buffer count: 100 small buffers
    { B b; for(int i=0;i<100;i++) u32be(b, 0x10); for(int i=0;i<100*0x10;i++) b.push_back(0x41); expect("100 small buffers (wrong count)", try_open(b), false); }
    printf(g_fail ? "RESULT FAIL: %d failing case(s)\n" : "RESULT OK: 0 failing case(s)\n", g_fail);
    return g_fail ? 1 : 0;
}
