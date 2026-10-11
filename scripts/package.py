"""Package sources and runnable examples, excluding tools, dependencies, and caches."""
from pathlib import Path
from zipfile import ZIP_DEFLATED, ZipFile

root = Path(__file__).resolve().parent.parent
destination = root.parent / "reflow-ascii-0.1.0.zip"
directories = {"src", "dist", "bin", "examples", "scripts", "test"}
files = {
    "package.json", "package-lock.json", "shadow-cljs.edn", "deps.edn",
    "README.md", "NOTICE.md", "LICENSE", "VALIDATION.md", ".gitignore",
    "index.html", "generator-standalone.html",
}

paths = sorted(
    path for path in root.rglob("*")
    if path.is_file()
    and (path.relative_to(root).parts[0] in directories
         or path.relative_to(root).as_posix() in files)
)

with ZipFile(destination, "w", compression=ZIP_DEFLATED, compresslevel=9) as archive:
    for path in paths:
        archive.write(path, Path(root.name) / path.relative_to(root))

with ZipFile(destination) as archive:
    bad_file = archive.testzip()
    if bad_file:
        raise RuntimeError(f"Archive verification failed: {bad_file}")

print(f"Created {destination.name}: {len(paths)} files, {destination.stat().st_size:,} bytes")
