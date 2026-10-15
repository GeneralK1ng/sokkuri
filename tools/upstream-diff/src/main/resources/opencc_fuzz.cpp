/*
 * Copyright 2026 The Sokkuri Authors and contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

// libFuzzer target behind `upstream-diff fuzz`: grows the differential's
// corpus from coverage of the reference implementation. Every input is
// converted and inspected by every profile under both tofu settings, so
// OpenCC's matching, segmentation and IDS code steer mutation toward inputs
// that reach them; the corpus libFuzzer keeps is replayed by `upstream-diff
// diff`, which is where the two implementations are actually compared.
//
// The Kotlin side configures the target through the environment:
//   OPENCC_FUZZ_CONFIG_DIR  the clone's data/config
//   OPENCC_FUZZ_DATA_DIR    the reference build's compiled dictionaries
//   OPENCC_FUZZ_PROFILES    comma-separated profile stems
//
// Inputs the differential cannot use are rejected before conversion, and
// returning -1 keeps them out of the corpus: anything but well-formed UTF-8
// (a Kotlin string's only byte form), line breaks (the harness reads one
// input per line) and NUL (upstream passes segments on as C strings).
// An exception OpenCC throws for an accepted input is not caught: libFuzzer
// records it as a crash, and it is a finding in its own right.

#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <sstream>
#include <string>
#include <vector>

#include "Config.hpp"
#include "Converter.hpp"

namespace {

// Strict UTF-8 (RFC 3629): no overlong forms, no surrogates, nothing above
// U+10FFFF; plus the bytes the line protocol and C strings cannot carry.
bool IsComparable(const uint8_t* data, size_t size) {
  size_t i = 0;
  while (i < size) {
    const uint8_t lead = data[i];
    if (lead == 0 || lead == '\n' || lead == '\r') return false;
    if (lead < 0x80) {
      i++;
      continue;
    }
    size_t continuation;
    uint32_t codePoint;
    if (lead >= 0xC2 && lead <= 0xDF) {
      continuation = 1;
      codePoint = lead & 0x1F;
    } else if (lead >= 0xE0 && lead <= 0xEF) {
      continuation = 2;
      codePoint = lead & 0x0F;
    } else if (lead >= 0xF0 && lead <= 0xF4) {
      continuation = 3;
      codePoint = lead & 0x07;
    } else {
      return false;
    }
    if (size - i <= continuation) return false;
    for (size_t k = 1; k <= continuation; k++) {
      const uint8_t byte = data[i + k];
      if ((byte & 0xC0) != 0x80) return false;
      codePoint = (codePoint << 6) | (byte & 0x3F);
    }
    if (continuation == 2 && codePoint < 0x800) return false;
    if (continuation == 3 && (codePoint < 0x10000 || codePoint > 0x10FFFF)) return false;
    if (codePoint >= 0xD800 && codePoint <= 0xDFFF) return false;
    i += continuation + 1;
  }
  return true;
}

const char* RequireEnvironment(const char* name) {
  const char* value = std::getenv(name);
  if (value == nullptr || *value == '\0') {
    std::fprintf(stderr, "opencc_fuzz: %s is not set (run it through `upstream-diff fuzz`)\n", name);
    std::abort();
  }
  return value;
}

std::vector<std::string> SplitProfiles(const std::string& value) {
  std::vector<std::string> stems;
  std::istringstream in(value);
  std::string stem;
  while (std::getline(in, stem, ',')) {
    if (!stem.empty()) stems.push_back(stem);
  }
  return stems;
}

std::vector<opencc::ConverterPtr> LoadConverters() {
  const std::vector<std::string> paths = {RequireEnvironment("OPENCC_FUZZ_CONFIG_DIR"),
                                          RequireEnvironment("OPENCC_FUZZ_DATA_DIR")};
  std::vector<opencc::ConverterPtr> converters;
  for (const std::string& stem : SplitProfiles(RequireEnvironment("OPENCC_FUZZ_PROFILES"))) {
    for (const bool includeTofuRisk : {false, true}) {
      opencc::Config config;
      opencc::ConfigLoadOptions options;
      options.includeTofuRiskDictionaries = includeTofuRisk;
      converters.push_back(config.NewFromFile(stem + ".json", paths, nullptr, options));
    }
  }
  if (converters.empty()) {
    std::fprintf(stderr, "opencc_fuzz: OPENCC_FUZZ_PROFILES names no profile\n");
    std::abort();
  }
  return converters;
}

}  // namespace

extern "C" int LLVMFuzzerTestOneInput(const uint8_t* data, size_t size) {
  if (!IsComparable(data, size)) return -1;
  static const std::vector<opencc::ConverterPtr> converters = LoadConverters();
  const std::string text(reinterpret_cast<const char*>(data), size);
  for (const opencc::ConverterPtr& converter : converters) {
    converter->Convert(text);
    converter->Inspect(text);
  }
  return 0;
}
