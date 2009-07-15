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

(defn reverse-line-map
  "Take a string consisting of space-separated values, returning a map from the second half to the first."
  [#^String lines]
  (letfn [(line->map-entry [#^String line]
            (let [space (int (.indexOf line (int \space)))]
              (when space
                [(.substring line (+ 1 space))
                 (.substring line 0 space)])))]
    (with-open [s (java.io.StringReader. lines)
                b (java.io.BufferedReader. s)]
      (into {} (seq (map line->map-entry (line-seq b)))))))

(defn refs->commits []
  (reverse-line-map
    (sh "git" "show-ref")))

(defn ref->commit
  "Takes a full refspec, such as \"refs/heads/master\"."
  [ref]
  ((reverse-line-map
     (sh "git" "show-ref" ref))
     ref))
    
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
