import sys

if len(sys.argv) > 1 and sys.argv[1] == "scan":
    from .cli import main
    sys.exit(main(sys.argv[2:]))

from .gui import main  # noqa: E402

sys.exit(main())
