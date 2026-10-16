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

//! `sokcheck`: the command line of the independent `.sok` reader
//! (docs/design/sok-rs.md).
//!
//! Exit status: 0 when every check passes, 1 when a file violates the format,
//! 2 on a usage or I/O error. Data goes to standard output, diagnostics to
//! standard error, and every output is deterministic: files in name order,
//! fixed formats.

mod files;
mod report;

use std::io::{self, Write};
use std::path::PathBuf;
use std::process::ExitCode;

use files::SokInput;

const USAGE: &str = "\
usage: sokcheck <command> [arguments]

commands:
  verify <path>...           check .sok files, or every .sok in a directory, against the acceptance rules
  dump <file>                list a file's entries: key, then its candidates, tab-separated
  stats <path>...            sizes and shape of each file
  expect --packaged <dir>    one expectation line per .sok in <dir>, for the Kotlin conformance test
  help                       this text

exit status: 0 when every check passes, 1 when a file violates the format, 2 on a usage or I/O error";

/// How a command ended, before it becomes an exit status.
enum Outcome {
    Passed,
    Violations,
}

/// Why a command could not finish.
enum Failure {
    /// A usage or I/O error: exit status 2.
    Error(String),
    /// Whoever read the output stopped reading, as `| head` does: the run
    /// ends quietly, like other command-line tools.
    BrokenPipe,
}

impl From<io::Error> for Failure {
    fn from(error: io::Error) -> Self {
        if error.kind() == io::ErrorKind::BrokenPipe {
            Self::BrokenPipe
        } else {
            Self::Error(error.to_string())
        }
    }
}

fn main() -> ExitCode {
    let arguments: Vec<String> = std::env::args().skip(1).collect();
    match run(&arguments) {
        Ok(Outcome::Passed) => ExitCode::SUCCESS,
        Ok(Outcome::Violations) => ExitCode::from(1),
        Err(Failure::BrokenPipe) => ExitCode::SUCCESS,
        Err(Failure::Error(message)) => {
            eprintln!("sokcheck: {message}");
            ExitCode::from(2)
        }
    }
}

fn run(arguments: &[String]) -> Result<Outcome, Failure> {
    let (command, rest) = arguments
        .split_first()
        .ok_or_else(|| Failure::Error(USAGE.to_owned()))?;
    let stdout = io::stdout();
    let mut out = stdout.lock();
    let outcome = match command.as_str() {
        "verify" => verify(&inputs(rest)?, &mut out)?,
        "dump" => match rest {
            [file] => dump(&files::read_file(&PathBuf::from(file))?, &mut out)?,
            _ => return Err(Failure::Error("dump takes exactly one file".to_owned())),
        },
        "stats" => stats(&inputs(rest)?, &mut out)?,
        "expect" => match rest {
            [flag, directory] if flag == "--packaged" => {
                expect(&files::read_directory(&PathBuf::from(directory))?, &mut out)?
            }
            _ => return Err(Failure::Error("expect takes --packaged <dir>".to_owned())),
        },
        "help" | "--help" | "-h" => {
            writeln!(out, "{USAGE}")?;
            Outcome::Passed
        }
        other => return Err(Failure::Error(format!("unknown command '{other}'\n{USAGE}"))),
    };
    out.flush()?;
    Ok(outcome)
}

/// The files named by `paths`, directories expanded to their `.sok` files.
fn inputs(paths: &[String]) -> Result<Vec<SokInput>, Failure> {
    if paths.is_empty() {
        return Err(Failure::Error("no file or directory given".to_owned()));
    }
    let mut inputs = Vec::new();
    for path in paths {
        inputs.extend(files::read_path(&PathBuf::from(path))?);
    }
    Ok(inputs)
}

fn verify(inputs: &[SokInput], out: &mut impl Write) -> Result<Outcome, Failure> {
    let mut outcome = Outcome::Passed;
    for input in inputs {
        match sok::SokFile::parse(&input.bytes) {
            Ok(file) => writeln!(out, "ok\t{}\t{} entries", input.name, file.len())?,
            Err(violation) => {
                writeln!(out, "violation\t{}\t{violation}", input.name)?;
                outcome = Outcome::Violations;
            }
        }
    }
    Ok(outcome)
}

fn dump(input: &SokInput, out: &mut impl Write) -> Result<Outcome, Failure> {
    match sok::SokFile::parse(&input.bytes) {
        Ok(file) => {
            report::write_dump(&input.name, &file, out)?;
            Ok(Outcome::Passed)
        }
        Err(violation) => {
            eprintln!("sokcheck: {}: {violation}", input.name);
            Ok(Outcome::Violations)
        }
    }
}

fn stats(inputs: &[SokInput], out: &mut impl Write) -> Result<Outcome, Failure> {
    let mut outcome = Outcome::Passed;
    for input in inputs {
        match sok::SokFile::parse(&input.bytes) {
            Ok(file) => report::write_stats(&input.name, &file, out)?,
            Err(violation) => {
                writeln!(out, "violation\t{}\t{violation}", input.name)?;
                outcome = Outcome::Violations;
            }
        }
    }
    Ok(outcome)
}

/// Expectations are data: a rejected file is a `reject` line, not a failure.
fn expect(inputs: &[SokInput], out: &mut impl Write) -> Result<Outcome, Failure> {
    for input in inputs {
        writeln!(out, "{}", report::expectation(&input.name, &input.bytes))?;
    }
    Ok(Outcome::Passed)
}
