(ns com.twinql.clojure.git
  (:refer-clojure)
  (:use clojure.contrib.str-utils)
  (:use clojure.contrib.shell-out))

(defmacro with-repo [repo & body]
  `(with-sh-dir ~repo
     ~@body))

(defn status []
  (sh "git" "status"))

(defn object-exists? [hash]
  (= 0 (:exit (sh :return-map true "git" "cat-file" "-e" hash))))

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
   (sh "git" "cat-file" (str as) hash)))
    
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
  "Each blob is a sequence of SHA1, kind, name."
  [blobs]
  (chomp
    (sh :in (apply str
              (seq
                (map (fn [[sha1 kind name]]
                       (str "100644 " (str kind) " "
                            sha1 \tab name \newline))
                     blobs)))
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

(defn tree-entry->map [e]
  (let [[all perms type sha1 filename]
        (re-matches #"^([0-9]{6}) ([a-z]+) ([0-9a-f]+{40})\t(.*)$"
                    e)]
    {:permissions perms
     :type type
     :object sha1
     :name filename}))

(defn commit->tree
  [commit]
  (with-line-seq [s (cat-object commit "commit")]
    (let [[what sha1] (split-space (first s))]
      (if (= what "tree")
        sha1
        (throw (new Exception
                    (str "Value is a " what ", not a tree.")))))))
   
(defn ls-tree
  ;; Might want to add recursion options here.
  ([tree]
   (with-line-seq [s (sh "git" "ls-tree" tree)]
     (doall (map tree-entry->map s)))))

(defn blob? [x]
  (= "blob" (:type x)))

(defn tree-contents
  "Not lazy to avoid any problems with bindings 'expiring'.
  Returns a map of file path to contents.
  Use a filter of blob? if you want to only fetch blobs."
  ([tree filt]
   (into {}
     (map (fn [x]
            [(:name x) (cat-object (:object x))])
          (filter filt (ls-tree tree)))))
  ([tree]
   (tree-contents tree (constantly true))))

(defn commit-tree
  [tree parent author committer message]
  (chomp
    (apply sh
           (concat
             [:in message
              :env {"GIT_AUTHOR_NAME" author
                    "GIT_COMMITTER_NAME" committer}
              "git" "commit-tree"]
             (when parent ["-p" parent])
             [tree]))))

(defn update-ref
  [ref commit]
  (chomp
    (sh "git" "update-ref" ref commit)))
