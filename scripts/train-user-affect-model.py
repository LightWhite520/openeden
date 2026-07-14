#!/usr/bin/env python3
"""CLI entry point for CUDA-only Qwen user-affect training."""

try:
    from scripts.user_affect_training import main
except ModuleNotFoundError:
    from user_affect_training import main


if __name__ == "__main__":
    main()
