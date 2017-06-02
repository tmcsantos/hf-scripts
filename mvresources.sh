#!/usr/bin/env bash

# Moves messages from src into resources, usefull when mavenizing
# run inside the src/main directory

find java -type d -name messages -print0 | \
while IFS= read -r -d $'\0' file; do d=${file%/*}; d=${d#*/}; \
mkdir -p resources/$d; \
git mv "$file" "resources/$d"; \
done