# Dependencies #

Only Clojure and contrib. Either modify `build.xml` or use `-Dclojure.jar=...`
and `-Dclojure.contrib.jar=...` when running `ant`.

At runtime this library requires `git` to be on the path.

# What is it? #

This is a Clojure front-end to `git`. Right now only low-level operations are
included; this is enough to allow very basic serialization of data into Git's
object store.

# Examples #

    user=> (use 'com.twinql.clojure.git)  
    nil

    ;; List refs and their commits -- returns a map.
    ;; with-repo is only necessary if your working directory is not the 
    ;; repo you want to access.
    user=> (with-repo "." (refs->commits))
    {"refs/remotes/origin/master" "eca2ada9fb79c50a0404e9fef4c0255f2fbe8a30",
     "refs/heads/master" "eca2ada9fb79c50a0404e9fef4c0255f2fbe8a30"}

    ;; Fetch the commit for a single ref.
    user=> (ref->commit "refs/remotes/origin/master")
    "eca2ada9fb79c50a0404e9fef4c0255f2fbe8a30"

    user=> (object-type "eca2ada9fb79c50a0404e9fef4c0255f2fbe8a30")
    "commit"

    user=> (object-size "eca2ada9fb79c50a0404e9fef4c0255f2fbe8a30")
    235

    ;; Let's write in an object.
    user=> (with-repo "/tmp/raw"
      (hash-object-from-string "Hello, world!" nil :write false))                 
    "5dd01c177f5d7d1be5346a5bc18a569a7410c2ef"

    user=> (with-repo "/tmp/raw"
      (cat-object "5dd01c177f5d7d1be5346a5bc18a569a7410c2ef"))
    "Hello, world!"

    ;; Make a tree and commit it.
    user=> (with-repo "/tmp/raw"
      (make-tree [["5dd01c177f5d7d1be5346a5bc18a569a7410c2ef" "blob" "some-file"]]))
    "1a4d231af53de9164b03296a4dcc18e2fcd715c6"

    user=> (with-repo "/tmp/raw"
      (commit-tree *1 (ref->commit "refs/heads/master") "Author" "Committer" "Test commit."))
    "f8455ab747e18e8bba7b14acbd92dcbf8c15b5fa"

    ;; Now update the ref.
    user=> (with-repo "/tmp/raw" 
      (update-ref "refs/heads/master" *1))
    ""

    ;; TADA!
    user=> (with-repo "/tmp/raw" (ref->commit "refs/heads/master"))
    "f8455ab747e18e8bba7b14acbd92dcbf8c15b5fa"

    ;; Fetch file contents for the tree identified by the head.
    user=> (with-repo "/tmp/raw"
      (tree-contents
        ((comp commit->tree ref->commit) "refs/heads/master")
        blob?))
    {"some-file" "Hello, world!"}

