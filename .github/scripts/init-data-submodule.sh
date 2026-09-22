#!/usr/bin/env bash
#
# Checks out the `data` submodule: the data-driven source catalogue and engine sources that the
# :engine module compiles into the app.
#
# This runs as its own step instead of through the checkout action's `submodules:` input so that a
# pin the remote does not carry is recovered here — or, failing that, reported named, annotated and
# with the remediation printed — rather than aborting the job inside the checkout action's git
# plumbing, and so that jobs which only read repository metadata do not need the submodule at all.
#
# Resolution order, first hit wins:
#   1. the submodule's remote, through the default fetch;
#   2. the remote's `branch` from .gitmodules, fetched explicitly (shallow/refspec-limited clones);
#   3. the bootstrap bundle under .github/data-bootstrap, which carries the pinned commits as git
#      objects so a clone of this repository builds before the branch reaches the remote.
#
# The bundle is a bootstrap, not a home for the sources. Once `branch` is published, step 2 resolves
# the pin from the remote, the bundle stops being read, and it should be deleted:
#
#   git -C data fetch "$PWD/.github/data-bootstrap/nyora-data-driven-android-sources-2026-09-20.bundle" \
#       '+refs/heads/android-sources-2026-09-20:refs/heads/android-sources-2026-09-20'
#   git -C data push origin android-sources-2026-09-20
#   git rm .github/data-bootstrap/nyora-data-driven-android-sources-2026-09-20.bundle
#
# It was produced from a checkout holding the pin with:
#
#   git -C data bundle create \
#       "$PWD/.github/data-bootstrap/nyora-data-driven-android-sources-2026-09-20.bundle" \
#       android-sources-2026-09-20 --not v0.2.1
#
# so `git bundle verify` needs only tag v0.2.1, which the remote publishes; the bundle adds nothing
# the remote does not already carry except the pinned commits themselves.
set -uo pipefail

marker="data/engine/EngineRegistry.kt"
url="$(git config -f .gitmodules submodule.data.url || true)"
branch="$(git config -f .gitmodules submodule.data.branch || true)"
pin="$(git ls-tree HEAD data | awk '{ print $3 }')"
bundle="$PWD/.github/data-bootstrap/nyora-data-driven-android-sources-2026-09-20.bundle"

fail() {
	echo "::error title=data submodule unavailable::$1"
	echo "The build needs commit ${pin:-<unknown>} of ${url:-the data submodule}${branch:+, carried by branch $branch}."
	echo "Fix it in one of these ways:"
	echo "  * from a checkout that has the commit, push it:"
	echo "      git push origin ${pin:-<commit>}:refs/heads/${branch:-main}"
	echo "  * push it straight out of this repository's bootstrap bundle:"
	echo "      git -C data fetch \"$bundle\" '+refs/heads/${branch:-main}:refs/heads/${branch:-main}'"
	echo "      git -C data push origin ${branch:-main}"
	echo "  * repin the data submodule to a commit the remote already carries;"
	echo "  * or build against a local checkout: ./gradlew -Pnyora.dataDrivenDir=/path/to/nyora-data-driven/data"
	exit 1
}

# True once the pinned commit is an object in the submodule checkout, however it got there.
have_pin() {
	[ -e data/.git ] && git -C data rev-parse --verify --quiet "$pin^{commit}" >/dev/null
}

# Puts the submodule on the pin. The failed update can leave it on an unborn HEAD, which
# `git submodule update` refuses to reconcile, so check the pin out directly and let the update
# settle the rest (nested submodules, .git/config wiring).
settle() {
	git -C data checkout --quiet --force "$pin" &&
		git submodule update --init --recursive
}

# Fetches the pin out of the bootstrap bundle. The bundle is thin: its prerequisite commit must
# already be in the checkout, which a full clone of the default branch has.
fetch_from_bundle() {
	[ -f "$bundle" ] && [ -e data/.git ] || return 1
	if ! git -C data bundle verify "$bundle" >/dev/null 2>&1; then
		# A shallow or single-branch clone can be missing the prerequisite; deepen and retry once.
		git -C data fetch --quiet --tags --unshallow origin >/dev/null 2>&1 ||
			git -C data fetch --quiet --tags origin >/dev/null 2>&1
		git -C data bundle verify "$bundle" >/dev/null 2>&1 || return 1
	fi
	git -C data fetch --quiet "$bundle" "+refs/heads/${branch:-main}:refs/remotes/origin/bootstrap-${branch:-main}"
}

git submodule sync --recursive || fail "git submodule sync failed."

if ! git submodule update --init --recursive; then
	resolved=""

	# A pin that is not a branch tip can need the carrying branch fetched explicitly: shallow and
	# refspec-limited fetches, and servers that refuse a bare sha, do not resolve it otherwise.
	if [ -n "$branch" ] && [ -e data/.git ]; then
		echo "Pinned commit not resolved by the default fetch; retrying through branch $branch."
		if git -C data fetch --no-tags origin "+refs/heads/$branch:refs/remotes/origin/$branch" &&
			have_pin && settle; then
			resolved="remote"
		fi
	fi

	if [ -z "$resolved" ] && [ -f "$bundle" ]; then
		echo "Falling back to the bootstrap bundle $(basename "$bundle")."
		if fetch_from_bundle && have_pin && settle; then
			resolved="bundle"
		fi
	fi

	case "$resolved" in
	remote) ;;
	bundle)
		echo "::notice title=data submodule bootstrapped from a bundle::Commit $pin is not on ${branch:+branch $branch of }${url:-the data remote} yet, so it was read from .github/data-bootstrap instead. Publish the branch (git -C data push origin ${branch:-main}) and delete the bundle."
		;;
	*)
		if [ -e data/.git ]; then
			fail "commit $pin is not on $url and the bootstrap bundle did not supply it either."
		else
			fail "the data submodule could not be cloned from $url."
		fi
		;;
	esac
fi

[ -f "$marker" ] || fail "the data submodule checked out but $marker is missing."

head="$(git -C data rev-parse HEAD)"
[ "$head" = "$pin" ] || fail "the data submodule is at $head, not the pinned $pin."

echo "data submodule at $head"
