# Resolving and replying to PR review comments

Applies after pushing code that addresses review feedback. Goal: every comment the push
answers ends up with a reply pointing at the commit, and a resolved thread. Never resolve a
thread whose feedback was not actually implemented.

Set these once per PR:

```bash
OWNER=egutierrezb REPO=claude_rest_be PR=1
```

## 1. Read every comment, including drafts

Submitted review comments:

```bash
gh api repos/$OWNER/$REPO/pulls/$PR/comments --jq '.[] | {id, path, line, body}'
```

**If that comes back empty but the reviewer says they left comments, the review is still a
draft.** A `PENDING` review is visible only to its author, its comments are absent from the
endpoint above, and fetching the comment id directly returns 404. Check for it:

```bash
gh api repos/$OWNER/$REPO/pulls/$PR/reviews --jq '.[] | {id, state, user: .user.login}'
# then, for a PENDING review, read its comments through the review itself:
gh api repos/$OWNER/$REPO/pulls/$PR/reviews/<REVIEW_ID>/comments --jq '.[] | {id, path, body}'
```

GraphQL sees pending threads too, and is the only way to get the thread IDs needed to
resolve anything:

```bash
gh api graphql -f query='
query($owner:String!, $repo:String!, $pr:Int!) {
  repository(owner:$owner, name:$repo) {
    pullRequest(number:$pr) {
      reviewThreads(first:50) {
        nodes {
          id isResolved isOutdated
          comments(first:10) { nodes { databaseId body path } }
        }
      }
    }
  }
}' -f owner=$OWNER -f repo=$REPO -F pr=$PR
```

Tell the author when a review is still `PENDING` — nobody else can see that feedback, so it
is almost always unintentional.

## 2. Fix, verify, then push

Run the suite before pushing, not after (`mvn test`). A review fix that reddens CI costs a
second round trip. If the suite was already failing before your change, say so explicitly
rather than letting it look like the fix broke it.

## 3. Reply to each comment

Reply in the thread, not as a top-level PR comment, so the discussion stays attached to the
line:

```bash
gh api repos/$OWNER/$REPO/pulls/$PR/comments/<COMMENT_ID>/replies -f body='...'
```

Name the commit SHA and what actually changed. "Done" forces the reviewer to go digging.

Exception: a thread from a `PENDING` review has no public existence, so a reply would
reference something no one else can see. Wait for the author to submit, or reply once at the
PR level and say why.

## 4. Resolve the thread

REST cannot do this — it is GraphQL only, using the thread `id` (`PRRT_…`) from step 1, not
the comment's numeric id:

```bash
gh api graphql -f query='
mutation($threadId:ID!) {
  resolveReviewThread(input:{threadId:$threadId}) {
    thread { id isResolved }
  }
}' -f threadId=<PRRT_ID>
```

Resolve only threads you genuinely addressed. For feedback you disagreed with or deferred,
leave the thread open and reply with the reasoning — resolving it hides the disagreement
instead of settling it.

Note that `isOutdated: true` only means the lines moved, not that the concern was handled.

## 5. Report back

State which threads were replied to, which were resolved, and which were deliberately left
open and why.
