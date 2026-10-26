"""
Deploy PolicyGuard (API + bundled UI) to a Hugging Face Docker Space.

One-time setup (you do this yourself, in your own terminal):
    pip install huggingface_hub
    hf auth login            # paste a WRITE token from https://huggingface.co/settings/tokens

Then:
    python deploy/deploy_hf.py --space <your-hf-username>/policyguard

The Space is created if it does not exist. Secrets (DATABASE_URL, GROQ_API_KEY, GEMINI_API_KEY,
ADMIN_KEY) are NOT uploaded by this script - add them in the Space's Settings page.
"""
import argparse
import pathlib
import shutil
import tempfile

from huggingface_hub import HfApi

ROOT = pathlib.Path(__file__).resolve().parent.parent


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--space", required=True, help="e.g. abhaypateria/policyguard")
    args = parser.parse_args()

    api = HfApi()
    api.create_repo(args.space, repo_type="space", space_sdk="docker", exist_ok=True)

    with tempfile.TemporaryDirectory() as tmp:
        out = pathlib.Path(tmp)
        for name in ["Dockerfile", ".dockerignore", "pom.xml"]:
            shutil.copy(ROOT / name, out / name)
        shutil.copytree(ROOT / "src", out / "src")
        shutil.copytree(ROOT / "frontend", out / "frontend", ignore=shutil.ignore_patterns("node_modules", "dist"))
        shutil.copy(ROOT / "deploy" / "hf" / "README.md", out / "README.md")
        api.upload_folder(folder_path=str(out), repo_id=args.space, repo_type="space",
                          commit_message="Deploy PolicyGuard")

    user, name = args.space.split("/")
    print(f"Uploaded. Build logs: https://huggingface.co/spaces/{args.space}")
    print(f"App URL (after the build, ~5-8 min): https://{user}-{name}.hf.space")


if __name__ == "__main__":
    main()
