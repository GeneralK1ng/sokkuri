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

// The OpenCC side of tools:upstream-diff: a small driver over the reference
// build of the pinned clone, one subcommand per kind of comparison.
//
//   convert <stem> <configDir> <dataDir> <tofu:0|1>
//       stdin: one input per line; stdout: one converted line per input.
//
//   inspect <stem> <configDir> <dataDir> <tofu:0|1>
//       stdin: one input per line; stdout: one JSON object per input, the
//       converter's ConversionInspectionResult as upstream builds it:
//         {"segments": [string], "stages": [{"index": n, "segments": [string]}],
//          "pipelineStages": [<same object, recursively>], "output": string}
//       Strings are UTF-8 with JSON escapes for '"', '\\' and control
//       characters; mapping this tree onto Sokkuri's flatter Inspection is the
//       Kotlin side's job, so this side only serializes.
//
// Why a driver rather than the `opencc` CLI: the CLI feeds its input through
// ConverterStream, which keeps a tail of 16 code points so a phrase or IDS
// sequence can span chunk boundaries. A trailing IDS operator therefore
// consumes the newline and the first character of the next line, the whole of
// stdin behaves as one document, and a line-by-line comparison against a
// per-call converter reports divergences that are not there.
// Converter::Convert() is const and converts its argument independently, which
// is the contract Sokkuri's per-string conversion is comparable against.
//
// Exit status: 0 on success, 2 on a usage error, 1 when OpenCC throws while
// loading or converting; the message goes to stderr.

#include <cstdio>
#include <exception>
#include <iostream>
#include <string>
#include <utility>
#include <vector>

#include "Config.hpp"
#include "ConversionInspection.hpp"
#include "Converter.hpp"
#include "Exception.hpp"

namespace {

// Where a profile's config and compiled dictionaries live, and which side of
// the tofu-risk setting to load it with.
struct ProfileSource {
  std::string configDir;
  std::string dataDir;
  bool includeTofuRisk;
};

class UsageError : public std::exception {
public:
  explicit UsageError(std::string message) : message(std::move(message)) {}
  const char* what() const noexcept override { return message.c_str(); }

private:
  std::string message;
};

// Parses the 0/1 flag the Kotlin side always passes; anything else is a
// caller bug, not a request for a default.
bool ParseFlag(const std::string& value, const char* name) {
  if (value == "0") return false;
  if (value == "1") return true;
  throw UsageError(std::string(name) + " must be 0 or 1, was '" + value + "'");
}

opencc::ConverterPtr LoadProfile(const std::string& stem,
                                 const ProfileSource& source) {
  opencc::Config config;
  opencc::ConfigLoadOptions options;
  // Upstream's C++ core defaults this to true and its CLI to false; the caller
  // states which side it wants so both implementations use one setting.
  options.includeTofuRiskDictionaries = source.includeTofuRisk;
  const std::vector<std::string> paths = {source.configDir, source.dataDir};
  return config.NewFromFile(stem + ".json", paths, nullptr, options);
}

// convert <stem> <configDir> <dataDir> <tofu>
int RunConvert(const std::vector<std::string>& args) {
  if (args.size() != 4) {
    throw UsageError("convert <stem> <configDir> <dataDir> <tofu:0|1>");
  }
  const ProfileSource source{args[1], args[2], ParseFlag(args[3], "tofu")};
  const opencc::ConverterPtr converter = LoadProfile(args[0], source);
  std::string line;
  while (std::getline(std::cin, line)) {
    std::cout << converter->Convert(line) << '\n';
  }
  return 0;
}

// Writes [value] as a JSON string. JSON text is UTF-8, so bytes at or above
// 0x80 pass through unchanged; only the quote, the backslash and the C0
// controls need escapes.
void WriteJsonString(std::ostream& out, const std::string& value) {
  out << '"';
  for (const char ch : value) {
    const unsigned char byte = static_cast<unsigned char>(ch);
    switch (byte) {
      case '"': out << "\\\""; break;
      case '\\': out << "\\\\"; break;
      case '\b': out << "\\b"; break;
      case '\f': out << "\\f"; break;
      case '\n': out << "\\n"; break;
      case '\r': out << "\\r"; break;
      case '\t': out << "\\t"; break;
      default:
        if (byte < 0x20) {
          char escape[7];
          std::snprintf(escape, sizeof escape, "\\u%04x", byte);
          out << escape;
        } else {
          out << ch;
        }
    }
  }
  out << '"';
}

void WriteJsonStrings(std::ostream& out, const std::vector<std::string>& values) {
  out << '[';
  for (size_t i = 0; i < values.size(); i++) {
    if (i > 0) out << ',';
    WriteJsonString(out, values[i]);
  }
  out << ']';
}

void WriteInspection(std::ostream& out,
                     const opencc::ConversionInspectionResult& result) {
  out << "{\"segments\":";
  WriteJsonStrings(out, result.segments);
  out << ",\"stages\":[";
  for (size_t i = 0; i < result.stages.size(); i++) {
    if (i > 0) out << ',';
    out << "{\"index\":" << result.stages[i].index << ",\"segments\":";
    WriteJsonStrings(out, result.stages[i].segments);
    out << '}';
  }
  out << "],\"pipelineStages\":[";
  for (size_t i = 0; i < result.pipelineStages.size(); i++) {
    if (i > 0) out << ',';
    WriteInspection(out, result.pipelineStages[i]);
  }
  out << "],\"output\":";
  WriteJsonString(out, result.output);
  out << '}';
}

// inspect <stem> <configDir> <dataDir> <tofu>
int RunInspect(const std::vector<std::string>& args) {
  if (args.size() != 4) {
    throw UsageError("inspect <stem> <configDir> <dataDir> <tofu:0|1>");
  }
  const ProfileSource source{args[1], args[2], ParseFlag(args[3], "tofu")};
  const opencc::ConverterPtr converter = LoadProfile(args[0], source);
  std::string line;
  while (std::getline(std::cin, line)) {
    WriteInspection(std::cout, converter->Inspect(line));
    std::cout << '\n';
  }
  return 0;
}

}  // namespace

int main(int argc, char** argv) {
  const std::vector<std::string> args(argv + 1, argv + argc);
  try {
    if (args.empty()) throw UsageError("<convert|inspect> ...");
    const std::string& command = args[0];
    const std::vector<std::string> rest(args.begin() + 1, args.end());
    if (command == "convert") return RunConvert(rest);
    if (command == "inspect") return RunInspect(rest);
    throw UsageError("unknown subcommand '" + command + "'");
  } catch (const UsageError& e) {
    std::cerr << "usage: opencc_harness " << e.what() << '\n';
    return 2;
  } catch (const opencc::Exception& e) {
    std::cerr << "opencc: " << e.what() << '\n';
    return 1;
  } catch (const std::exception& e) {
    std::cerr << "error: " << e.what() << '\n';
    return 1;
  }
}
