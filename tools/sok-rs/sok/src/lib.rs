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

//! An independent, read-only reader of Sokkuri's `.sok` dictionary format.
//!
//! The format's single specification is the KDoc of `SokFormatLayout` in
//! `sokkuri-resource`. This crate implements it again from that text alone,
//! without reference to the Kotlin decoder, so that the two readings can be
//! compared (docs/design/sok-rs.md): a rule the specification leaves out shows
//! up as a disagreement instead of being missed by both.
//!
//! The crate never writes `.sok`: the Kotlin encoder stays the only one (D6).

pub mod utf8;
