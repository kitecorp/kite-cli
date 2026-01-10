# Bump Version Skill

Use this skill when bumping the kite-cli version.

## Workflow

1. **Update version in build.gradle**
   ```bash
   # In kite-cli directory
   sed -i '' "s/version = 'OLD'/version = 'NEW'/" build.gradle
   ```

2. **Run Gradle to update version.json**
   ```bash
   ./gradlew updateVersionJson
   ```
   This task automatically updates `version.json` with the new version for GitHub Pages.

3. **Commit both files**
   ```bash
   git add build.gradle version.json
   git commit -m "chore: bump version to NEW"
   git push
   ```

4. **Update parent repo submodule reference**
   ```bash
   cd /Users/mimedia/IdeaProjects/kite
   git add kite-cli
   git commit -m "chore: update kite-cli to NEW"
   git push
   ```

## Important Notes

- **Never** bump version with just `sed` and commit - always run `./gradlew updateVersionJson` first
- The `version.json` file is served via GitHub Pages at `https://cli.kitelang.cloud/version.json`
- The `kite upgrade` command reads from this file to determine the latest version
- GitHub Pages may take 1-2 minutes to refresh after pushing

## Version Format

Use semantic versioning: `MAJOR.MINOR.PATCH` (e.g., `0.3.9`)

## Verification

After pushing, verify the update:
```bash
# Wait ~1 minute for GitHub Pages, then:
curl -s https://cli.kitelang.cloud/version.json | jq '.latest'
```