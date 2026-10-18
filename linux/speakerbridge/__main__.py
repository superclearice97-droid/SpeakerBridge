import sys

if len(sys.argv) > 1 and sys.argv[1] == "scan":
    from .cli import main
    sys.exit(main(sys.argv[2:]))
if len(sys.argv) > 1 and sys.argv[1] == "stream":
    from .cli import stream_main
    sys.exit(stream_main(sys.argv[2:]))

from .gui import main  # noqa: E402

sys.exit(main())
