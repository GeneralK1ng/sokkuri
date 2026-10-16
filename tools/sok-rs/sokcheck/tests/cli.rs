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

#![allow(clippy::indexing_slicing)]

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
empty.sok\taccept\t0\t0\ta8c7f832281a39c5\taudit-ok
many-candidates.sok\taccept\t1\t1\t485b319d0ddcff85\taudit-ok
prefixes.sok\taccept\t3\t3\td48af38172a39280\taudit-ok
widths.sok\taccept\t7\t3\t257f553a9f91686e\taudit-ok
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
fn audit_reports_every_finding_and_exits_one() {
    let mut bytes = std::fs::read(fixtures().join("prefixes.sok")).expect("fixture");
    bytes[28] = 7;
    bytes[67 + 3..67 + 6].copy_from_slice(b"aaa");
    let odd = scratch("audit", "odd.sok", &bytes);
    let short = odd.with_file_name("short.sok");
    std::fs::write(&short, b"SOK1").expect("short.sok");
    let clean = fixtures().join("widths.sok");
    let output = sokcheck(&[
        "audit",
        clean.to_str().expect("path"),
        odd.parent().and_then(|p| p.to_str()).expect("path"),
    ]);
    assert_eq!(Some(1), output.status.code());
    let lines: Vec<String> = stdout(&output)
        .lines()
        .map(|line| line.split(": ").next().unwrap_or(line).to_owned())
        .collect();
    assert_eq!(
        vec![
            "ok\twidths.sok\taudit clean",
            "finding\todd.sok\tA1 at byte 70",
            "finding\todd.sok\tA4 at byte 28",
            "violation\tshort.sok\tV1 at byte 0",
        ],
        lines,
    );
    let expect = sokcheck(&[
        "expect",
        "--packaged",
        odd.parent().and_then(|p| p.to_str()).expect("path"),
    ]);
    assert!(
        stdout(&expect).starts_with("odd.sok\taccept\t3\t3\t"),
        "{}",
        stdout(&expect)
    );
    assert!(stdout(&expect).contains("\taudit:A1,A4\n"), "{}", stdout(&expect));
}

#[test]
fn audit_passes_on_the_fixtures() {
    let output = sokcheck(&["audit", fixtures().to_str().expect("path")]);
    assert_eq!(Some(0), output.status.code());
    assert_eq!(
        4,
        stdout(&output)
            .lines()
            .filter(|line| line.ends_with("\taudit clean"))
            .count()
    );
}

#[test]
fn a_corpus_imports_exports_and_expects_in_id_order() {
    // The inputs in a directory of their own, as libFuzzer keeps them; the
    // corpus file beside it, so an import never reads the corpus back in.
    let base = scratch("corpus", "placeholder", b"")
        .parent()
        .expect("parent")
        .to_path_buf();
    let directory = base.join("inputs");
    std::fs::create_dir_all(&directory).expect("inputs directory");
    std::fs::write(directory.join("a"), b"SOK1").expect("an input");
    std::fs::copy(fixtures().join("prefixes.sok"), directory.join("b")).expect("seed");
    std::fs::write(directory.join("large"), vec![0u8; 513]).expect("an input over the limit");
    let file = base.join("corpus.hex");
    let path = |p: &std::path::Path| p.to_str().expect("path").to_owned();

    let imported = sokcheck(&["corpus", "import", &path(&directory), &path(&file)]);
    assert_eq!(
        Some(0),
        imported.status.code(),
        "{}",
        String::from_utf8_lossy(&imported.stderr)
    );
    assert!(
        stdout(&imported).starts_with("2 new, 0 already known, 1 over 512 bytes skipped; 2 inputs"),
        "{}",
        stdout(&imported)
    );
    let again = sokcheck(&["corpus", "import", &path(&directory), &path(&file)]);
    assert!(
        stdout(&again).starts_with("0 new, 2 already known"),
        "{}",
        stdout(&again)
    );

    let expected = sokcheck(&["expect", "--corpus", &path(&file)]);
    assert_eq!(Some(0), expected.status.code());
    let lines: Vec<String> = stdout(&expected).lines().skip(1).map(str::to_owned).collect();
    assert_eq!(2, lines.len(), "{lines:?}");
    let mut ids: Vec<&str> = lines.iter().map(|line| &line[..16]).collect();
    let sorted = {
        let mut copy = ids.clone();
        copy.sort_unstable();
        copy
    };
    assert_eq!(sorted, ids);
    assert!(lines.iter().any(|line| line.ends_with("\treject\tV1")), "{lines:?}");
    assert!(
        lines
            .iter()
            .any(|line| line.contains("\taccept\t3\t3\td48af38172a39280\taudit-ok")),
        "{lines:?}"
    );

    let exported = base.join("exported");
    assert_eq!(
        Some(0),
        sokcheck(&["corpus", "export", &path(&file), &path(&exported)])
            .status
            .code()
    );
    let mut names: Vec<String> = std::fs::read_dir(&exported)
        .expect("exported")
        .map(|entry| entry.expect("entry").file_name().to_string_lossy().into_owned())
        .collect();
    names.sort();
    ids.sort_unstable();
    assert_eq!(ids, names);
    assert_eq!(std::fs::read(fixtures().join("prefixes.sok")).expect("fixture"), {
        let id = lines
            .iter()
            .find(|line| line.contains("accept"))
            .map(|line| line[..16].to_owned())
            .expect("accept line");
        std::fs::read(exported.join(id)).expect("exported input")
    });
}

#[test]
fn stale_corpus_expectations_are_reported() {
    let base = scratch("stale", "placeholder", b"")
        .parent()
        .expect("parent")
        .to_path_buf();
    let inputs = base.join("inputs");
    std::fs::create_dir_all(&inputs).expect("inputs");
    std::fs::write(inputs.join("a"), b"SOK1").expect("input");
    let corpus = base.join("corpus.hex");
    let expected = base.join("expected.txt");
    let path = |p: &std::path::Path| p.to_str().expect("path").to_owned();
    sokcheck(&["corpus", "import", &path(&inputs), &path(&corpus)]);
    std::fs::write(&expected, sokcheck(&["expect", "--corpus", &path(&corpus)]).stdout).expect("expected");

    let fresh = sokcheck(&["expect", "--corpus", &path(&corpus), "--check", &path(&expected)]);
    assert_eq!(Some(0), fresh.status.code(), "{}", stdout(&fresh));

    std::fs::copy(fixtures().join("prefixes.sok"), inputs.join("b")).expect("a second input");
    sokcheck(&["corpus", "import", &path(&inputs), &path(&corpus)]);
    let stale = sokcheck(&["expect", "--corpus", &path(&corpus), "--check", &path(&expected)]);
    assert_eq!(Some(1), stale.status.code());
    assert!(stdout(&stale).contains("is stale from line"), "{}", stdout(&stale));

    std::fs::write(&expected, "# a hand-edited file\n").expect("edited");
    let edited = sokcheck(&["expect", "--corpus", &path(&corpus), "--check", &path(&expected)]);
    assert_eq!(Some(1), edited.status.code());
    assert!(stdout(&edited).contains("is stale from line 1"), "{}", stdout(&edited));
}

#[test]
fn a_corrupted_corpus_is_refused() {
    let file = scratch("corrupted", "corpus.hex", b"0000000000000000\t534f4b31\n");
    let output = sokcheck(&["expect", "--corpus", file.to_str().expect("path")]);
    assert_eq!(Some(2), output.status.code());
    assert!(String::from_utf8_lossy(&output.stderr).contains("does not match its bytes"));
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
        &["audit"],
        &["dump"],
        &["dump", fixtures, fixtures],
        &["expect", "corpus.hex"],
        &["expect", "--unknown", fixtures],
        &["expect", "--corpus", "/no/such/corpus.hex"],
        &["corpus", "import", fixtures],
        &["corpus", "sideways", fixtures, fixtures],
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
