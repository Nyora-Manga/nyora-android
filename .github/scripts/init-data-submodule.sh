#!/usr/bin/env bash
#
# Checks out the `data` submodule: the data-driven source catalogue and engine sources that the
# :engine module compiles into the app.
#
# This runs as its own step instead of through the checkout action's `submodules:` input so that a
# pin the remote does not carry fails here — named, annotated and with the remediation printed —
# rather than aborting the job inside the checkout action's git plumbing, and so that jobs which
# only read repository metadata do not need the submodule at all.
set -uo pipefail

marker="data/engine/EngineRegistry.kt"
url="$(git config -f .gitmodules submodule.data.url || true)"
branch="$(git config -f .gitmodules submodule.data.branch || true)"
pin="$(git ls-tree HEAD data | awk '{ print $3 }')"

fail() {
	echo "::error title=data submodule unavailable::$1"
	echo "The build needs commit ${pin:-<unknown>} of ${url:-the data submodule}${branch:+, carried by branch $branch}."
	echo "Fix it in one of these ways:"
	echo "  * push the pinned commit to that remote;"
	echo "  * repin the data submodule to a commit the remote already carries;"
	echo "  * or build against a local checkout: ./gradlew -Pnyora.dataDrivenDir=/path/to/nyora-data-driven/data"
	exit 1
}

git submodule sync --recursive || fail "git submodule sync failed."

if ! git submodule update --init --recursive; then
	# A pin that is not a branch tip can need the carrying branch fetched explicitly: shallow and
	# refspec-limited fetches, and servers that refuse a bare sha, do not resolve it otherwise.
	if [ -n "$branch" ] && [ -e data/.git ]; then
		echo "Pinned commit not resolved by the default fetch; retrying through branch $branch."
		git -C data fetch --no-tags origin "+refs/heads/$branch:refs/remotes/origin/$branch" ||
			fail "branch $branch cannot be fetched from $url."
		# The failed update can leave the submodule on an unborn HEAD, which `git submodule update`
		# refuses to reconcile, so put it on the pin directly and let the update settle the rest.
		git -C data checkout --quiet --force "$pin" || fail "commit $pin is not reachable on $url."
		git submodule update --init --recursive || fail "the data submodule cannot be checked out at $pin."
	else
		fail "commit $pin is not available on $url."
	fi
fi

[ -f "$marker" ] || fail "the data submodule checked out but $marker is missing."

echo "data submodule at $(git -C data rev-parse HEAD)"
