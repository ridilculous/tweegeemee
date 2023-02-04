(ns tweegeemee.core
  (:use [clisk live])
  (:require
   ;;[clisk.live] tried this, but caused issues
   [clisk.patterns]
   [tweegeemee.image   :as image]
   [tweegeemee.twitter :as twitter]
   [tweegeemee.gists   :as gists]
   [environ.core       :refer [env]]
   [clojure.string     :as string]
   [clojure.java.io    :as io]
   [clojure.java.jdbc  :as sql])
  (:import [java.io File]
           [javax.imageio ImageIO])
  (:gen-class))

;; ======================================================================
(defonce NUM-PARENT-TWEETS 200)   ;; 24 posts/day * 2 imgs = 48 img/day = ~4 days
(defonce MAX-POSSIBLE-PARENTS 10) ;; top 10 tweets become parents

;; ======================================================================
(defonce my-twitter-creds    (atom nil)) ;; oauth from api.twitter.com
(defonce my-screen-name      (atom nil)) ;; twitter screen name
(defonce my-gist-auth        (atom nil)) ;; gist username:password
(defonce my-gist-archive-id  (atom nil)) ;; create this archive
(defonce my-pg-db            (atom nil))
(defonce my-old-hashes       (atom nil))
(defonce my-old-image-hashes (atom nil))

;; ======================================================================
(defn write-png
  "write the-image to filename (which should have a .png suffix)"
  [filename the-image]
  (ImageIO/write the-image "png" (File. filename)))

(defn- get-timestamp-str
  "return a timestamp for the current time"
  []
  (.format (java.text.SimpleDateFormat. "yyMMdd_HHmmss") (System/currentTimeMillis)))

(defn- get-our-file-seq
  "return a file-seq of our images/*.clj and images/*.png files"
  []
  (filter #(re-matches #".*\.clj|.*\.png" (.getName %)) (file-seq (io/file "images"))))

(comment
  (defn- cleanup-our-files!
    "DEPRECATED. remove all of our images/*.clj and images/*.png files"
    []
    (dorun (map #(io/delete-file %) (get-our-file-seq)))))

(defn- get-png-filename
  [timestamp suffix]
  (str "images/" timestamp "_" suffix ".png"))

(defn- get-clj-filename
  [timestamp suffix]
  (str "images/" timestamp "_" suffix ".clj"))

(defn- get-json-filename
  [timestamp suffix]
  (str "images/" timestamp "_" suffix ".json"))

(defn- get-clj-basename
  [timestamp suffix]
  (str timestamp "_" suffix ".clj"))

(defn- make-code-and-png*
  "make code from code-fn and save as files. return the code,
  clj-filename and png-filename"
  [code-fn timestamp suffix]
  (let [my-code (code-fn)]
    (if (nil? my-code)
      [nil nil nil]
      (let [png-filename (get-png-filename timestamp suffix)
            my-image     (clisk.live/image (eval my-code) :size image/IMAGE-SIZE)
            clj-filename (get-clj-filename timestamp suffix)
            json-filename (get-json-filename timestamp suffix)
            clj-basename (get-clj-basename timestamp suffix)]
        (write-png png-filename my-image)
        (spit clj-filename (pr-str my-code))
        [my-code clj-basename png-filename json-filename]))))

(declare get-random-code)
(declare get-random-child)
(declare get-random-mutant)
(defn- make-random-code-and-png
  "make random code and save as files. return the code, clj-filename,
  png-filename and json-filename"
  [timestamp suffix]
  (make-code-and-png* get-random-code timestamp suffix))

(defn- make-random-child-and-png
  "take 2 codes, breed them and save as files. return the code,
  clj-filename, png-filename and json-filename"
  [code0 code1 timestamp suffix]
  (make-code-and-png* (partial get-random-child code0 code1)
                      timestamp suffix))

(defn- make-random-mutant-and-png
  "take a code, mutate it and save as files. return the code,
  clj-filename, png-filename and json-filename"
  [code timestamp suffix]
  (make-code-and-png* (partial get-random-mutant code)
                      timestamp suffix))

(defn- cur-hour
  "return the current hour"
  []
  (.get (java.util.Calendar/getInstance) java.util.Calendar/HOUR_OF_DAY))

;; fix https://github.com/rogerallen/tweegeemee/issues/14
;; add twitter id to gist data
(defn- find-twitter-id
  "find this entry's :id_str (twitter-id) inside the statuses by matching
  the :name of the entry to the start of the status :text.  if not found,
  return nil."
  [entry statuses]
  (let [regex  (re-pattern (str "^" (:name entry)))
        status (first (filter #(re-find regex (:text %)) statuses))]
    (if (not (empty? status))
      (:id_str status)
      nil)))

(defn- add-id-to-entry
  "if necessary, try to find the twitter id for entry in statuses.  if
  you cannot find the id, return unchanged."
  [entry statuses]
  (if (:twitter-id entry)
    entry
    (if-let [twitter-id (find-twitter-id entry statuses)]
      (assoc entry :twitter-id twitter-id)
      entry)))

(defn- add-ids-to-entries
  [entries statuses]
  (map #(add-id-to-entry % statuses) entries))

(defn- get-tweegeemee-url
  "return canonical tweegeemee.com per-item url.  remove '.clj' from clj-filename
  to get the key for the url"
  [clj-filename]
  (let [key (subs clj-filename 0 (- (count clj-filename) 4))]
    (str "https://tweegeemee.com/i/" key)))

;; ======================================================================
;; Public API ===========================================================
;; ======================================================================
;; add these keys to your environment or
;; profiles.clj AND DON'T CHECK THAT FILE IN !!!
;; # twitter stuff:
;; export APP_CONSUMER_KEY="FIXME"
;; export APP_CONSUMER_SECRET="FIXME"
;; export USER_ACCESS_TOKEN="FIXME"
;; export USER_ACCESS_SECRET="FIXME"
;; export SCREEN_NAME="FIXME"
;; # github gist stuff:
;; export GIST_AUTH="FIXME" (use personal access token)
;; export GIST_ARCHIVE_ID="FIXME" (last part of URL)
;; # just pick a random integer
;; export CLISK_RANDOM_SEED="FIXME"
(defn setup-env!
  "setup environment vars"
  []
  (reset! my-twitter-creds   (twitter/make-oauth-creds
                              (env :app-consumer-key)
                              (env :app-consumer-secret)
                              (env :user-access-token)
                              (env :user-access-secret)))
  (reset! my-pg-db {:dbtype "postgresql"
                    :dbname (env :db-name)
                    :host (env :db-host)
                    :port (env :db-port)
                    :user (env :db-user)
                    :password (env :db-user-pass)})
  (reset! my-screen-name     (env :screen-name))
  (println "my-screen-name" @my-screen-name)
  (reset! my-gist-auth       (env :gist-auth))
  (reset! my-gist-archive-id (env :gist-archive-id))
  (when-let [seed (Integer/parseInt (env :clisk-random-seed))]
    (println "clisk-random-seed" seed)
    (clisk.patterns/seed-perlin-noise! seed)
    (clisk.patterns/seed-simplex-noise! seed))
  nil)

(defn- set-old-hashes!
  "reset my-old-hashes and image-hashes from the gist archive."
  []
  (let [data (gists/read-archive my-gist-auth my-gist-archive-id)
        old-hashes (set (map :hash data))
        old-image-hashes (set (map :image-hash data))]
    (reset! my-old-hashes old-hashes)
    (reset! my-old-image-hashes old-image-hashes)))

(defn- set-old-hashes-from-db!
  "return a vector of :hash and :image-hash data from the DB."
  []
  (let [random-seed (Integer/parseInt (env :clisk-random-seed))
        data (sql/query
              @my-pg-db
              ["SELECT hash,image_hash FROM items WHERE random_seed = ?"
               random-seed])
        old-hashes (set (map :hash data))
        old-image-hashes (set (map :image_hash data))
        _ (println "Current generation seed" random-seed "has" (count data) "items.")]
    (reset! my-old-hashes old-hashes)
    (reset! my-old-image-hashes old-image-hashes)))

(defn get-random-code
  "get a good image-creation code created randomly"
  []
  (image/get-random-code my-old-hashes my-old-image-hashes))

(defn get-random-child
  "get a good image-creation code created via breeding two other
  codes"
  [code0 code1]
  (image/get-random-child my-old-hashes my-old-image-hashes code0 code1))

(defn get-random-mutant
  "get a good image-creation code created via mutating a code"
  [code]
  (image/get-random-mutant my-old-hashes my-old-image-hashes code))

;; DEPRECATED--see below
(defn score-status
  "return a score for a image's tweet based on favorites and
  retweets. retweets count more than favorites"
  [status]
  (+ (* 3 (:retweet_count status)) (:favorite_count status)))

;; DEPRECATED--now getting this from DB
(comment
  (defn get-parent-tweets
    "return a sequence of the N highest-scoring tweets.  Tweet data is
     from the gist file."
    [N]
    (let [archive  (gists/read-archive my-gist-auth my-gist-archive-id)
          statuses (->> (twitter/get-statuses my-twitter-creds my-screen-name NUM-PARENT-TWEETS)
                        (map #(update-in % [:text] string/replace #".clj[\d\D]*" ".clj"))
                        (filter #(re-matches #"\d\d\d\d\d\d_\d\d\d\d\d\d_\w+.clj" (:text %)))
                        (map #(assoc % :score (score-status %)))
                        (sort-by :score)
                        (reverse)
                        (map :text)
                        (mapcat #(filter (fn [x] (= (:name x) %)) archive))
                        (filter #(image/sanity-check-code (str (:code %))))
                        (take N)
                        ;;((fn [x] (println "post-take:" x) x))
                        (map #(update-in % [:code] (fn [x] (read-string (str x))))))]
      statuses)))

(defn score-status1
  "return a score for a image's tweet based on favorites and 
   retweets. retweets count more than favorites--updated for Mastodon 
   retweets & favorites."
  [status]
  (+ (* 3 (+ (:retweet_count status) (:retweet_count1 status)))
     (+ (:favorite_count status) (:favorite_count status))))

(defn get-parent-tweets-from-db
  "return a sequence of the N highest-scoring tweets.  Tweet data is
   from the local database.  Data from DB is slightly different than from GIST,
   but current use of :code and :name matches"
  [N]
  (let [statuses (->> (sql/query
                       @my-pg-db
                       ["SELECT * FROM items WHERE gist_id = ? ORDER BY key DESC LIMIT ?"
                        @my-gist-archive-id NUM-PARENT-TWEETS])
                      (map #(assoc % :score (score-status1 %)))
                      (sort-by :score)
                      (reverse)
                      (take N)
                      (map #(update-in % [:code] (fn [x] (read-string (str x))))))]
    statuses))

(defn get-parent-tweets-from-db-new
  "return a sequence of the N highest-scoring tweets.  Tweet data is
   from the local database and should match the random-seed.  No longer
   using the gist website."
  [random-seed N]
  (let [statuses (->> (sql/query
                       @my-pg-db
                       ["SELECT * FROM items WHERE random_seed = ? ORDER BY key DESC LIMIT ?"
                        random-seed NUM-PARENT-TWEETS])
                      (map #(assoc % :score (score-status1 %)))
                      (sort-by :score)
                      (reverse)
                      (take N)
                      (map #(update-in % [:code] (fn [x] (read-string (str x))))))]
    statuses))

(defn post-to-web
  "Post the-code + clj-filename info to gist.github.com, post
  png-filename & a pointer to twitter."
  [the-code clj-filename png-filename parent-vec]
  (let [image-hash       (image/image-hash (clisk.live/image (eval the-code) :size image/TEST-IMAGE-SIZE))
        gist-line-number (gists/append-archive my-gist-auth my-gist-archive-id clj-filename the-code parent-vec image-hash)
        gist-url         (gists/get-url my-gist-archive-id gist-line-number)
        tweegeemee-url   (get-tweegeemee-url clj-filename)
        status-text      (str clj-filename "\n" tweegeemee-url "\n" gist-url
                              "\n#ProceduralArt #generative")]
    (twitter/post-image-file my-twitter-creds status-text png-filename)))

(defn save-locally
  "Save the-code & info to a local directory. 
   clj-file png-file have already been saved"
  [the-code json-filename parent-vec]
  (let [image-hash (image/image-hash (clisk.live/image (eval the-code) :size image/TEST-IMAGE-SIZE))
        code-hash (hash the-code)
        random-seed (env :clisk-random-seed)
        parent0 (first parent-vec)
        parent1 (first (rest parent-vec))
        ;; a bit of a hack, but it works...
        my-json-str (str
                     "{\n"
                     "    \"parents\": ["
                     (if (nil? parent0)
                       ""
                       (if (nil? parent1)
                         (str "\"" parent0 "\"")
                         (str "\"" parent0 "\", \"" parent1 "\"")))
                     "],\n"
                     "    \"hash\": " (str code-hash) ",\n"
                     "    \"image_hash\": " (str image-hash) ",\n"
                     "    \"random_seed\": " random-seed "\n"
                     "}\n")]
    (spit json-filename my-json-str)))

(defn post-batch-to-web*
  "Post a batch of random codes & images to twitter and github."
  [make-fn post-str suffix-str parent-vec]
  (dorun
   (doseq [suffix suffix-str]
     (let [_ (println post-str "begin" suffix)
           [the-code clj-filename png-filename _] (make-fn suffix)]
       (if (nil? the-code)
         (println "!!! suffix" suffix "unable to create" post-str "image")
         (post-to-web the-code clj-filename png-filename parent-vec)))))
  (println post-str "done."))

(defn make-batch*
  "Make & save a batch of random codes & images, plus save info about them in a json file."
  [make-fn post-str suffix-str parent-vec]
  (dorun
   (doseq [suffix suffix-str]
     (let [_ (println post-str "begin" suffix)
           [the-code _ _ json-filename] (make-fn suffix)]
       (if (nil? the-code)
         (println "!!! suffix" suffix "unable to create" post-str "image")
         (save-locally the-code json-filename parent-vec)))))
  (println post-str "done."))

(defn post-random-batch-to-web
  "Post a batch of random codes & images to twitter and github."
  [suffix-str]
  (let [timestamp-str (get-timestamp-str)]
    (post-batch-to-web* (partial make-random-code-and-png timestamp-str)
                        "random" suffix-str [])))

(defn make-random-batch
  "Make a batch of random codes & images."
  [suffix-str]
  (let [timestamp-str (get-timestamp-str)]
    (make-batch* (partial make-random-code-and-png timestamp-str)
                 "random" suffix-str [])))

(defn rand-nth-or-nil
  "protect from empty list rand-nth"
  [lst]
  (if (= 0 (count lst))
    nil
    (rand-nth lst)))

(defn post-children-to-web
  "Find the highest-scoring parents, post a batch of codes & images
  bred from those parents to twitter and github"
  [suffix-str]
  (let [timestamp-str (get-timestamp-str)
        rents         (get-parent-tweets-from-db MAX-POSSIBLE-PARENTS)
        c0            (rand-nth-or-nil rents)
        c1            (rand-nth-or-nil rents)
        can-work      (and (some? c0) (some? c1))]
    (if can-work
      (post-batch-to-web* (partial make-random-child-and-png (:code c0) (:code c1) timestamp-str)
                          "repro" suffix-str [(:name c0) (:name c1)])
      (println "INFO: No parents.  Cannot create child to post."))))

(defn make-children
  "Find the highest-scoring parents, make a batch of codes & images
  bred from those parents"
  [suffix-str]
  (let [timestamp-str (get-timestamp-str)
        rents         (get-parent-tweets-from-db-new (Integer/parseInt (env :clisk-random-seed)) MAX-POSSIBLE-PARENTS)
        c0            (rand-nth-or-nil rents)
        c1            (rand-nth-or-nil rents)
        can-work      (and (some? c0) (some? c1))]
    (if can-work
      (make-batch* (partial make-random-child-and-png (:code c0) (:code c1) timestamp-str)
                   "repro" suffix-str [(:name c0) (:name c1)])
      (println "INFO: No parents.  Cannot create child to post."))))

(defn post-mutants-to-web
  "Find the highest-scoring parents, post a batch of 5 codes & images
  mutated from the top parent to twitter and github"
  [suffix-str]
  (let [timestamp-str (get-timestamp-str)
        rents         (get-parent-tweets-from-db MAX-POSSIBLE-PARENTS)
        c0            (rand-nth-or-nil rents)
        can-work      (some? c0)]
    (if can-work
      (post-batch-to-web* (partial make-random-mutant-and-png (:code c0) timestamp-str)
                          "mutant" suffix-str [(:name c0)])
      (println "INFO: No parent.  Cannot create mutant to post."))))

(defn make-mutants
  "Find the highest-scoring parents, post a batch of 5 codes & images
  mutated from the top parent to twitter and github"
  [suffix-str]
  (let [timestamp-str (get-timestamp-str)
        rents         (get-parent-tweets-from-db-new (Integer/parseInt (env :clisk-random-seed)) MAX-POSSIBLE-PARENTS)
        c0            (rand-nth-or-nil rents)
        can-work      (some? c0)]
    (if can-work
      (make-batch* (partial make-random-mutant-and-png (:code c0) timestamp-str)
                   "mutant" suffix-str [(:name c0)])
      (println "INFO: No parent.  Cannot create mutant to post."))))

;; fix https://github.com/rogerallen/tweegeemee/issues/14
;; add twitter id to gist data
;; this is no longer required
(defn reconcile-gist-twitter-ids
  []
  (loop [entries (gists/read-archive my-gist-auth my-gist-archive-id)
         statuses (twitter/get-statuses my-twitter-creds my-screen-name twitter/MAX-GET-STATUS-COUNT)]
    (let [entries            (add-ids-to-entries entries statuses)
          entries-without-id (filter #(not (:twitter-id %)) entries)
          last-id            (:id (last statuses))]
      (if (or (= (count entries-without-id) 0)
              (= (count statuses) 1))
        ;; fixed all entries or ran out of statuses
        ;;(println "WRITE ->" entries)
        (gists/write-archive my-gist-auth my-gist-archive-id entries)
        (recur entries (twitter/get-statuses my-twitter-creds my-screen-name twitter/MAX-GET-STATUS-COUNT last-id)))))
  nil)

;; ======================================================================
;; main entry
;; ======================================================================
(defn new-main [args]
  (println "posting one of: random ab, children CD or mutant MN")
  (setup-env!)
  (let [gen (if (empty? args)
              (mod (cur-hour) 3)
              (Integer/parseInt (first args)))]
    (set-old-hashes-from-db!)
    (println "posting gen=" gen)
    (case gen
      0 (make-random-batch "ab")
      1 (make-children "CD")
      2 (make-mutants "MN"))
    (println "posting complete.")
    (shutdown-agents) ;; quit faster
    (println "shutdown agents")
    (twitter/stop)
    (println "stopped twitter")
    0))

(defn original-main []
  (println "posting one of: random ab, children CD or mutant MN")
  (setup-env!)
  (set-old-hashes!)
  (case (mod (cur-hour) 3)
    0 (post-random-batch-to-web "ab")
    1 (post-children-to-web "CD")
    2 (post-mutants-to-web "MN"))
  (reconcile-gist-twitter-ids)
  ;; not cleaning up after ourselves any longer
  ;; leaving the files allows for local archival and posts to more social networks
  ;; and requires a separate process to clean up those files
  ;; (cleanup-our-files!)
  (println "posting complete.")
  (shutdown-agents) ;; quit faster
  (println "shutdown agents")
  (twitter/stop)
  (println "stopped twitter")
  0)  ;; return 0 status so we don't look like we crashed

(defn -main [& args]
  (println "======================================================================")
  (println "Started version" (env :tweegeemee-version))
  (if (seq args)
    (if (= (first args) "new")
      (new-main (rest args))
      1)
    (original-main)))
