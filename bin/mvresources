#!/bin/bash

# run inside the src/main directory

find java -type f -not -name *.java -print0 | \
while IFS= read -r -d $'\0' file; do d=${file%/*}; d=${d#*/}; \
mkdir -p resources/$d; git mv "$file" "resources/$d"; \
done