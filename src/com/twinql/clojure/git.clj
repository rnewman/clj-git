(ns com.twinql.clojure.git
  (:refer-clojure)
  (:use clojure.contrib.str-utils)
  (:use clojure.contrib.shell-out))

(defmacro with-repo [repo & body]
  `(with-sh-dir (or ~repo *sh-dir*)     ; So repo can be nil.
     ~@body))

(defn git-kind [x]
  (letfn [(err []
            (throw (new Exception
                        (str "Invalid object type '" x "'."))))]
    (cond
      (instance? String x)
      (if (#{"blob" "tag" "commit" "tree"} x)
        x
        (err))
      
      (keyword? x)
      (if (#{:blob :tag :commit :tree} x)
        (name x)
        (err)))))
    
(defn make-repo [dir]
  (sh "mkdir" "-p" dir)
  (with-sh-dir dir
    (sh "git" "init")))
 
(defn status []
  (sh "git" "status"))

(defn object-exists? [hash]
  (zero? (:exit (sh :return-map true "git" "cat-file" "-e" hash))))

(defn object-size [hash]
  (Integer/parseInt
    (chomp
      (sh "git" "cat-file" "-s" hash))))

(defn object-type [hash]
  (chomp
    (sh "git" "cat-file" "-t" hash)))
  
(defn cat-object
  "Returns the contents as a string."
  ([hash]
   (sh "git" "cat-file" "blob" hash))
  ([hash as]
   (sh "git" "cat-file" (git-kind as) hash)))
    
(defn hash-object-from-string
  "Returns the SHA-1."
  [object path write? filters?]
  (if (and (not filters?)
           path)
    (throw (Exception. "Cannot combine path with no-filters."))
    (chomp
      (apply sh
             :in object
             (concat
               ["git" "hash-object" "--stdin"]
               (when path ["--path" path])
               (when write? ["-w"])
               (when (not filters?) ["--no-filters"]))))))

(defn make-tree
  "Each entry is a sequence of perms, kind, SHA1, name.
  If perms is nil, the default will be used for the kind."
  [entries]
  (chomp
    (sh :in (apply str
              (seq
                (map (fn [[perms kind sha1 name]]
                       (let [k (git-kind kind)]
                         (cond
                           ;; TODO: how do I handle tags and commits?
                           (= k "tag")
                           (str (or perms "040000") " tag " sha1 \tab name \newline)
                           (= k "commit")
                           (str (or perms "040000") " commit " sha1 \tab name \newline)
                           (= k "tree")
                           (str (or perms "040000") " tree " sha1 \tab name \newline)
                           (= k "blob")
                           (str (or perms "100644") " blob " sha1 \tab name \newline))))
                     entries)))
        "git" "mktree")))

(defmacro with-line-seq [[s #^String lines] & body]
  `(with-open [ss# (java.io.StringReader. ~lines)
               bb# (java.io.BufferedReader. ss#)]
     (let [~s (line-seq bb#)]
       ~@body)))

(defn- split-space [#^String line]
  (let [space (int (.indexOf line (int \space)))]
    (when space
      [(.substring line 0 space)
       (.substring line (+ 1 space))])))

(definline flip [[x y]] [y x])

(defn reverse-line-map
  "Take a string consisting of space-separated values, returning a map from the second half to the first."
  [#^String lines]
  (with-line-seq [s lines]
    (into {} (seq (map (comp flip split-space) s)))))

(defn refs->commits []
  (reverse-line-map
    (sh "git" "show-ref")))

(defn ref->commit
  "Takes a full refspec, such as \"refs/heads/master\"."
  [ref]
  ((reverse-line-map
     (sh "git" "show-ref" ref))
     ref))

(defn ensure-ref->commit
  "Return the commt for the given ref, or throw an Exception."
  [ref]
  (or (ref->commit ref)
      (throw (new Exception
                        (str "No commit for ref " ref "
                             in repo " *sh-dir* ".")))))

(defn new-branch [name start-point]
  (if start-point
    (sh "git" "branch" "-l" name start-point)
    (sh "git" "branch" "-l" name)))

(defn- branches->list [str]
  (with-line-seq [s str]
    (doall
      (map (fn [#^String x] (.substring x 2)) s))))

(defn branches
  ([]
   (branches->list (sh "git" "branch" "--no-color")))
  ([kind commit]
   (if-let [option ({:merged "--merged"
                     :no-merged "--no-merged"
                     :contains "--contains"} kind)]
     (branches->list (sh "git" "branch" "--no-color" option commit))
     (throw (new Exception
                 (str "Unrecognized option to `git branch`: " kind))))))
 
(defn tree-entry->map [e]
  (let [[all perms type sha1 filename]
        (re-matches #"^([0-9]{6}) ([a-z]+) ([0-9a-f]+{40})\t(.*)$"
                    e)]
    {:permissions perms
     :type type
     :object sha1
     :name filename}))
  
(defn tree-entry-seq
  "Returns a 4-element sequence for an ls-tree row:
       perms type object	filename"
  [e]
  (rest (re-matches #"^([0-9]{6}) ([a-z]+) ([0-9a-f]+{40})\t(.*)$" e)))

(defn sha?
  "Returns a true value (the hash itself) if `x` is a 40-character hash.
  Only permits 0-9a-f, not A-F."
  [x]
  (re-find #"^[0-9a-f]{40}$" x))
  
(defn to-commit
  "Turns just about anything into a commit."
  [x]
  (let [commit (chomp (sh "git" "rev-parse" "--verify" x))]
    (if (sha? commit)
      commit
      (throw (new Exception
                  (str x " does not name a single commit. Error was:\n"
                       commit))))))
  
(defn commit->tree
  [commit]
  (when commit
    (with-line-seq [s (cat-object commit "commit")]
      (let [[what sha1] (split-space (first s))]
        (if (= what "tree")
          sha1
          (throw (new Exception
                      (str "Value is a " what ", not a tree."))))))))
   
(defn ls-tree
  ;; Might want to add recursion options here.
  ([tree]
   (if (nil? tree)
     (throw (new Exception "nil passed to ls-tree."))
     (with-line-seq [s (sh "git" "ls-tree" tree)]
       (doall (map tree-entry-seq s))))))

(defn adding-to-tree
  "Returns a tree listing consisting of entries from the selected tree
  plus the new entries. New entries of the same name overwrite the old.
  This operation is not recursive."
  [tree new-entries]
  (let [old-listing (ls-tree tree)]
    (if (or (nil? old-listing)
            (empty? old-listing))
      new-entries
      (concat
        (let [names (set (map #(nth % 3) new-entries))]
          (filter (comp (complement names) #(nth % 3))
                  old-listing))
        new-entries))))
  
(defn blob? [x]
  (= "blob" (second x)))

(defn tree-contents
  "Not lazy to avoid any problems with bindings 'expiring'.
  Returns a map of file path to contents.
  Use a filter of blob? if you want to only fetch blobs."
  ([tree filt]
   (into {}
     (map (fn [x]
            [(nth x 3) (cat-object (nth x 2))])
          (filter filt (ls-tree tree)))))
  ([tree]
   (tree-contents tree (constantly true))))

(defn commit-tree
  [tree parent author committer message]
  (chomp
    (apply sh (concat
                [:in message
                 :env {"GIT_AUTHOR_NAME" author
                       "GIT_COMMITTER_NAME" committer}
                 "git" "commit-tree" tree]
                (when parent ["-p" parent])))))

(defn update-ref
  [ref commit]
  (chomp
    (sh "git" "update-ref" ref commit)))
  
(defn checkout [branch]
  (chomp
    (apply sh ["git" "checkout" branch])))
  
(defn merge-current
  [remote]
  (chomp
    (apply sh ["git" "merge" remote])))

(defn pull [repo]
  (chomp
    (apply sh ["git" "pull" repo])))
  
(defn push [to & opts]
  (let [{:keys [refspecs all? mirror? tags?]} opts]
    (chomp
      (apply sh
             (concat
               ["git" "push" "--porcelain"]
               (cond
                 all? ["--all"]
                 mirror? ["--mirror"]
                 tags? ["--tags"])
               [to]
               (if (string? refspecs)
                 [refspecs]
                 refspecs))))))
