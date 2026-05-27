// SPDX-License-Identifier: MIT
// Copyright (c) the purl authors
//
// Exposes `Purl` and `PurlCanonical` types compiled from the ABNF grammar
// extracted from `docs/standard/grammar.md` by the build script.

include!(concat!(env!("OUT_DIR"), "/purl_types.rs"));
