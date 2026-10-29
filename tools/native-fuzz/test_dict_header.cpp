// Host check for HeaderReadWriteUtils::isHeaderValid and the guarded attribute read.
#include <cstdio>
#include <cstdint>
#include <vector>
#include "dictionary/header/header_read_write_utils.h"
using namespace latinime;
typedef std::vector<uint8_t> B;
static void u32(B &b, uint32_t v) { for (int i = 3; i >= 0; i--) b.push_back((v >> (8 * i)) & 0xff); }
static B hdr(uint32_t declared, const B &attrs, bool pad_to_declared = false) {
    B b; u32(b, 0x9BC13AFE); b.push_back(0); b.push_back(0x03); b.push_back(0); b.push_back(0); u32(b, declared);
    b.insert(b.end(), attrs.begin(), attrs.end());
    return b;
}
static B attr(const char *k, const char *v) { B b; for (const char *p = k; *p; p++) b.push_back(*p); b.push_back(0x1F); for (const char *p = v; *p; p++) b.push_back(*p); b.push_back(0x1F); return b; }
static int check(const char *name, const B &b, bool expect) {
    bool ok = HeaderReadWriteUtils::isHeaderValid(b.data(), b.size());
    printf("%s %s: %s (expected %s)\n", ok == expect ? "PASS" : "FAIL", name, ok ? "valid" : "rejected", expect ? "valid" : "reject");
    return ok == expect ? 0 : 1;
}
int main() {
    int f = 0;
    { B a = attr("locale", "en_US"); B a2 = attr("date", "123"); a.insert(a.end(), a2.begin(), a2.end()); f += check("well formed, 2 attributes", hdr(12 + a.size(), a), true); }
    f += check("no attributes", hdr(12, B()), true);
    f += check("buffer shorter than fixed fields", B{1, 2, 3}, false);
    { B a = attr("k", "v"); f += check("declared size larger than file", hdr(12 + a.size() + 1000, a), false); }
    f += check("declared size 0xFFFFFFFF", hdr(0xFFFFFFFFu, B()), false);
    f += check("declared size smaller than fixed fields", hdr(4, B()), false);
    { B a = attr("k", "v"); a.pop_back(); f += check("unterminated value string", hdr(12 + a.size(), a), false); }
    { B a = attr("k", "v"); B x = a; x.push_back(0x05); x.push_back(0x41); f += check("3-byte code point straddles header end", hdr(12 + x.size() - 1, x), false); }
    { B a = attr("k", "v"); a.push_back('x'); a.push_back(0x1F); f += check("odd number of strings (key without value)", hdr(12 + a.size(), a), false); }
    // The writer never emits these: an even (or zero) terminator count and an exact-end walk,
    // but the header does not end on a terminator. The attribute reader runs off the end of
    // such a header, so the guard must reject them.
    { B a; a.push_back('x'); f += check("no strings, one stray byte", hdr(12 + a.size(), a), false); }
    { B a = attr("k", "v"); a.push_back('x'); f += check("complete pair then one stray byte", hdr(12 + a.size(), a), false); }
    { B a = attr("k", "v"); a.push_back('a'); a.push_back('b'); f += check("complete pair then unterminated string", hdr(12 + a.size(), a), false); }
    // The guard must reject before the attribute reader is reached: accepted here means the
    // reader walks past the buffer (ASan reports it), so this fails loudly either way.
    { B a; a.push_back('x'); B b = hdr(12 + a.size(), a);
      if (HeaderReadWriteUtils::isHeaderValid(b.data(), b.size())) {
          DictionaryHeaderStructurePolicy::AttributeMap m;
          HeaderReadWriteUtils::fetchAllHeaderAttributes(b.data(), &m);
          printf("FAIL guard let a non-terminated header reach the attribute reader\n");
          ++f;
      } else {
          printf("PASS non-terminated header rejected before the attribute reader\n");
      } }
    printf("%s: %d failing case(s)\n", f ? "RESULT FAIL" : "RESULT OK", f);
    return f ? 1 : 0;
}
