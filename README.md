# Plainly

**GitHub without the Git.** Plainly is a single-page web app for people who want to keep their projects on GitHub but don't want to learn Git. Browse, edit, upload, undo and merge from your browser, using everyday words instead of Git jargon.

There is no server and no build step. The whole app is one file, `index.html`.

- **Repository:** https://github.com/Anithing8/Plainly
- **Live app (GitHub Pages):** https://anithing8.github.io/Plainly/

## What you can do

| Plainly says | Git calls it | What it does |
| --- | --- | --- |
| Version | commit | A saved snapshot. Every change you make creates one. |
| Draft | branch | A separate copy of your work to try things safely. |
| Restore this version | revert to an old commit | Brings back an older version without erasing anything. |
| Pull request | pull request | Sends a draft's changes back to the main version. |

- **Repositories**: list and filter yours, create new ones, edit name, description and visibility, delete (with a typed confirmation).
- **Files**: browse folders, edit text files, create files, rename or move files and folders, download. Delete one item, tick several and delete them together, or delete everything in a folder. Changes save in the background, so you can keep working.
- **Upload**: drop in a `.zip` (it is opened for you), loose files, or a whole folder. Everything lands as one version. Files with the same path are replaced.
- **Versions**: see history and restore any earlier version.
- **Drafts**: create, switch and delete.
- **Pull requests**: open one from a draft, then merge (merge, squash or rebase) or close it.

## Getting started

1. Create a **fine-grained personal access token** at GitHub → Settings → Developer settings → Personal access tokens → Fine-grained tokens.
2. Choose the repositories it may use, then set these repository permissions:

   | Permission | Level | Needed for |
   | --- | --- | --- |
   | Contents | Read and write | Everything involving files |
   | Pull requests | Read and write | The Pull requests tab |
   | Administration | Read and write | Creating, editing or deleting repositories |
   | Workflows | Read and write | Uploading `.github/workflows` files |
   | Metadata | Read | Added automatically |

3. Open the app, paste the token and sign in.

## Running it

**Locally:** download `index.html` from the [repository](https://github.com/Anithing8/Plainly) and open it in a browser. You need an internet connection (it talks to GitHub and loads JSZip from a CDN).

**On GitHub Pages:**
1. Put `index.html` at the top level of a repository.
2. Go to Settings → Pages, choose *Deploy from a branch*, pick your main branch and the `/ (root)` folder, then save.
3. This repository's copy lives at `https://anithing8.github.io/Plainly/`. If you host your own copy, the address is `https://YOUR-USERNAME.github.io/YOUR-REPO/`.

## Privacy and security

- Your token is only ever sent to `api.github.com`. Nothing is sent to any other server, and there are no analytics.
- If you tick **Remember me**, the token is saved in your browser's local storage on that device. Don't use this on a shared computer, and sign out when you're done.
- Use a token limited to the repositories you need, and give it an expiry date.

## Known limits

- Very large repositories can't be bulk-renamed or bulk-deleted from the browser (GitHub truncates the file listing).
- Binary files such as images can be uploaded and downloaded but not edited.
- Folders that become empty disappear, because Git doesn't track empty folders.
- No download of a whole repository as a zip, issues, forks, or side-by-side change comparison.

## License

MIT. See [LICENSE](LICENSE).
