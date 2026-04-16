#!/usr/bin/env python3
"""Compatibility entry point for the AutoEq offline converter.

This wrapper preserves older command/docs references that use `autoeq_convert.py`
while reusing the maintained implementation in `autoeq_converter.py`.
"""

from autoeq_converter import main


if __name__ == "__main__":
    raise SystemExit(main())

