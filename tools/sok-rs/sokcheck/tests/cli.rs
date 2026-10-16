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

//! The command line end to end: outputs, formats and exit statuses.

use std::path::PathBuf;
use std::process::{Command, Output, Stdio};

fn fixtures() -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../fixtures")
}

fn sokcheck(arguments: &[&str]) -> Output {
    Command::new(env!("CARGO_BIN_EXE_sokcheck"))
        .args(arguments)
        .output()
        .expect("sokcheck runs")
}

fn stdout(output: &Output) -> String {
    String::from_utf8(output.stdout.clone()).expect("UTF-8 output")
}

/// A file in a directory of its own, so tests running in parallel never see each other's files.
fn scratch(test: &str, name: &str, bytes: &[u8]) -> PathBuf {
    let directory = std::env::temp_dir().join(format!("sokcheck-cli-{}-{test}", std::process::id()));
    let _ = std::fs::remove_dir_all(&directory);
    std::fs::create_dir_all(&directory).expect("scratch directory");
    let path = directory.join(name);
    std::fs::write(&path, bytes).expect("scratch file");
    path
}

#[test]
fn expect_lists_every_file_in_name_order_with_its_digest() {
    let output = sokcheck(&["expect", "--packaged", fixtures().to_str().expect("path")]);
    assert_eq!(Some(0), output.status.code());
    // The digests were computed by an independent Python reading of the stream.
    let expected = "\
empty.sok\taccept\t0\t0\ta8c7f832281a39c5
many-candidates.sok\taccept\t1\t1\t485b319d0ddcff85
prefixes.sok\taccept\t3\t3\td48af38172a39280
widths.sok\taccept\t7\t3\t257f553a9f91686e
";
    assert_eq!(expected, stdout(&output));
}

#[test]
fn a_rejected_file_is_an_expectation_not_a_failure() {
    let path = scratch("rejected", "short.sok", b"SOK1");
    let output = sokcheck(&[
        "expect",
        "--packaged",
        path.parent().and_then(|p| p.to_str()).expect("path"),
    ]);
    assert_eq!(Some(0), output.status.code());
    assert_eq!("short.sok\treject\tV1\n", stdout(&output));
}

#[test]
fn verify_exits_one_on_a_violation_and_names_it() {
    let good = fixtures().join("prefixes.sok");
    let bad = scratch(
        "verify",
        "bad-magic.sok",
        b"XOK1________________________________________",
    );
    let output = sokcheck(&["verify", good.to_str().expect("path"), bad.to_str().expect("path")]);
    assert_eq!(Some(1), output.status.code());
    let text = stdout(&output);
    assert!(text.starts_with("ok\tprefixes.sok\t3 entries\n"), "{text}");
    assert!(text.contains("violation\tbad-magic.sok\tV2 at byte 0: "), "{text}");
}

#[test]
fn a_directory_contributes_only_its_sok_files() {
    let directory = scratch("listing", "b.sok", b"SOK1")
        .parent()
        .expect("parent")
        .to_path_buf();
    std::fs::write(directory.join("a.sok"), b"SOK1").expect("a.sok");
    std::fs::write(directory.join("notes.txt"), b"not a dictionary").expect("notes.txt");
    std::fs::create_dir_all(directory.join("nested.sok")).expect("a directory named like a dictionary");
    let output = sokcheck(&["expect", "--packaged", directory.to_str().expect("path")]);
    assert_eq!(Some(0), output.status.code());
    assert_eq!("a.sok\treject\tV1\nb.sok\treject\tV1\n", stdout(&output));
}

#[test]
fn verify_of_a_directory_passes_on_the_fixtures() {
    let output = sokcheck(&["verify", fixtures().to_str().expect("path")]);
    assert_eq!(Some(0), output.status.code());
    assert_eq!(4, stdout(&output).lines().count());
}

#[test]
fn dump_lists_keys_then_candidates() {
    let output = sokcheck(&["dump", fixtures().join("prefixes.sok").to_str().expect("path")]);
    assert_eq!(Some(0), output.status.code());
    let expected =
        "# prefixes.sok: 3 entries, maxKeyLength 3, keyBlobBytes 6, valueBlobBytes 12\na\tA\nab\tAB\nabc\tABC\n";
    assert_eq!(expected, stdout(&output));
}

#[test]
fn stats_describe_the_shape() {
    let output = sokcheck(&["stats", fixtures().join("widths.sok").to_str().expect("path")]);
    assert_eq!(Some(0), output.status.code());
    let text = stdout(&output);
    assert!(text.contains("  entries          7\n"), "{text}");
    assert!(
        text.contains("  key starts       5 distinct, the largest 2 keys under U+4E2D\n"),
        "{text}"
    );
    assert!(
        text.contains("  supplementary    2 keys start outside the BMP\n"),
        "{text}"
    );
    assert!(
        text.contains("  candidates       11, at most 3 per entry, 3 entries with several\n"),
        "{text}"
    );
}

#[test]
fn usage_and_io_errors_exit_two() {
    let fixtures = fixtures();
    let fixtures = fixtures.to_str().expect("path");
    for arguments in [
        &[][..],
        &["nope"],
        &["verify"],
        &["dump"],
        &["dump", fixtures, fixtures],
        &["expect", "corpus.hex"],
        &["expect", "--unknown", fixtures],
        &["verify", "/no/such/file.sok"],
    ] {
        let output = sokcheck(arguments);
        assert_eq!(Some(2), output.status.code(), "{arguments:?}");
        assert!(output.stdout.is_empty(), "{arguments:?}");
        assert!(!output.stderr.is_empty(), "{arguments:?}");
    }
    assert_eq!(Some(0), sokcheck(&["help"]).status.code());
}

#[test]
fn a_reader_that_stops_reading_ends_the_run_quietly() {
    let mut child = Command::new(env!("CARGO_BIN_EXE_sokcheck"))
        .args(["dump", fixtures().join("many-candidates.sok").to_str().expect("path")])
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .spawn()
        .expect("sokcheck runs");
    drop(child.stdout.take());
    let output = child.wait_with_output().expect("sokcheck ends");
    assert!(output.stderr.is_empty(), "{}", String::from_utf8_lossy(&output.stderr));
}
