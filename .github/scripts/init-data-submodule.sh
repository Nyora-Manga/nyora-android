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
#   2. the remote's `branch` from .gitmodules, fetched explicitly (shallow/refspec-limited clones).
set -uo pipefail

marker="data/engine/EngineRegistry.kt"
url="$(git config -f .gitmodules submodule.data.url || true)"
branch="$(git config -f .gitmodules submodule.data.branch || true)"
pin="$(git ls-tree HEAD data | awk '{ print $3 }')"

fail() {
	echo "::error title=data submodule unavailable::$1"
	echo "The build needs commit ${pin:-<unknown>} of ${url:-the data submodule}${branch:+, carried by branch $branch}."
	echo "Fix it in one of these ways:"
	echo "  * from a checkout that has the commit, push it:"
	echo "      git push origin ${pin:-<commit>}:refs/heads/${branch:-main}"
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

	case "$resolved" in
	remote) ;;
	*)
		if [ -e data/.git ]; then
			fail "commit $pin is not on $url."
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
