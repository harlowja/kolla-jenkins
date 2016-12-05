#!/usr/bin/env python

from __future__ import print_function

import os
import sys
import traceback

import pathlib2

from jenkins_jobs import parser


def main():
    if len(sys.argv) > 1:
        paths = sys.argv[1:]
    else:
        paths = [os.getcwd()]
    paths = [pathlib2.Path(path) for path in paths]
    yaml_paths = []
    while paths:
        path = paths.pop()
        if path.is_dir():
            paths.extend(path.iterdir())
        elif path.is_file():
            if path.suffix == '.yaml':
                yaml_paths.append(path)
    bad = 0
    ok = 0
    for path in yaml_paths:
        try:
            p = parser.YamlParser()
            p.parse(str(path))
            ok += 1
        except Exception:
            print("Path %s was not ok!!" % path, file=sys.stderr)
            traceback.print_exc()
            bad += 1
    print("Validated %s yaml files, %s were"
          " ok, %s were bad." % (ok + bad, ok, bad))
    if bad:
        sys.exit(1)
    else:
        sys.exit(0)


if __name__ == '__main__':
    main()
