#!/usr/bin/env bash
# Builds the Threat Studio bundle into the extension resources, assembles the jar (which
# `otoroshi/lib/vendor-waf.jar` symlinks to) and nudges the dev otoroshi into reloading it.
(cd ui/threat-studio && npm install --no-audit --no-fund && npm run build) && sbt assembly && date > /Users/mathieuancelin/projects/otoroshi/otoroshi/app/reload.diff
