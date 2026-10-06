// Per-input OpenCC harness for the upstream differential.
//
// Why this exists rather than shelling out to the `opencc` CLI: the CLI drives
// input through ConverterStream, which retains a tail of 16 code points so a
// phrase or IDS sequence can span chunk boundaries. A trailing IDS operator
// therefore consumes the newline and the first character of the next line, so
// the whole of stdin behaves as one document and a line-by-line comparison
// against a per-call converter reports divergences that are not there.
//
// Converter::Convert() is const and converts its argument independently, so
// this harness calls it once per input line. That is the contract Sokkuri's
// per-string conversion is comparable against.
//
// argv: <stem> <configDir> <dataDir> [tofuRisk]
//   reads inputs from stdin, one per line, writes one output per line.

#include <iostream>
#include <string>
#include <vector>

#include "Config.hpp"
#include "Converter.hpp"

int main(int argc, char** argv) {
  if (argc < 4) {
    std::cerr << "usage: harness <stem> <configDir> <dataDir> [tofuRisk]\n";
    return 2;
  }
  const std::string stem = argv[1];
  const std::vector<std::string> paths = {argv[2], argv[3]};

  opencc::Config config;
  opencc::ConfigLoadOptions options;
  // Upstream's C++ core defaults this to true and its CLI to false; the
  // caller passes which side of that it wants so both ends use one setting.
  options.includeTofuRiskDictionaries = (argc > 4 && std::string(argv[4]) == "1");

  auto converter = config.NewFromFile(stem + ".json", paths, nullptr, options);

  std::string line;
  while (std::getline(std::cin, line)) {
    std::cout << converter->Convert(line) << "\n";
  }
  return 0;
}
