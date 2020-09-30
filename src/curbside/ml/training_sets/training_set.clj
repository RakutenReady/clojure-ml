(ns curbside.ml.training-sets.training-set
  "Provides a general training set abstraction to be used by all ml models.
  Contains utilities to load a training set from csv files and for various
  operations such as splitting.

  A training set is defined as a map containing the following keys:

  - `:features` : a vector of keyword indicating which feature is present in each
     feature map. Note that the order is important, as the features are inputted
     in this order in ML models.
  - `:feature-maps` : a vector of maps. Each map represents a single training example.
  - `:labels` : a vector of numbers of the same length as `:feature-maps`. Lists the
     labels associated to each example.
  - `:weights` : a vector of numbers. Importance to attribute to each example,
     or each group for ranking.
  - `:groups` : a vector of integers. Used for ranking. Indicates how many
    successive examples are part of the same group. For instance, `[2 2 2]` means
    that we have six examples partitioned into three groups of two. The first two
    examples are in the same group, the two next examples are in the second group
    and the two last are in the last group. This is the group semantic used by
    XGBoost."
  (:require
   [clojure.spec.alpha :as s]
   [curbside.ml.utils.io :as io-utils]
   [curbside.ml.utils.spec :as spec-utils]
   [curbside.ml.training-sets.conversion :as conversion]
   [clojure.java.io :as io]))

;; =============================================================================
;; Spec
;; =============================================================================

(s/def ::features (s/and (s/coll-of keyword?) vector?))
(s/def ::feature-maps (s/and (s/coll-of map?) vector?))
(s/def ::groups (s/and (s/coll-of number?) vector?))
(s/def ::labels (s/and (s/coll-of number?) vector?))
(s/def ::weights (s/and (s/coll-of number?) vector?))

(defn- valid-label-count?
  [{:keys [feature-maps labels]}]
  (= (count feature-maps) (count labels)))

(defn- valid-weight-count?
  [{:keys [feature-maps groups weights]}]
  (or
   (and (nil? weights) (nil? groups))
   (if (seq groups)
     (= (count weights) (count groups))
     (= (count weights) (count feature-maps)))))

(defn- valid-groups?
  [{:keys [labels groups]}]
  (or (nil? groups)
      (= (count labels)
         (apply + groups))))

(s/def ::training-set
  (s/and
   (s/keys :req-un [::features
                    ::feature-maps
                    ::labels]
           :opt-un [::groups
                    ::weights])
   valid-label-count?
   valid-weight-count?
   valid-groups?))

;; =============================================================================
;; CSV loading/saving
;; =============================================================================

(defn- load-groups
  "Load groups from a groups csv filepath. The file must contain a single
  `group` column."
  [filepath]
  (mapv :group (conversion/csv-to-maps filepath)))

(defn- load-weights
  "Load weights from a weights csv filepath. The file must contain a single
  `weight` column."
  [filepath]
  (mapv :weight (conversion/csv-to-maps filepath)))

(defn load-csv-files
  "Loads a training-set map from csv files. The `training-set-path` must be
  provided, while the others are optional. If `groups-path` is specified but not
  `weights-path`, a default weight of 1.0 is attributed to each group."
  [training-set-path weights-path groups-path]
  {:post [(spec-utils/check ::training-set %)]}
  (let [features (rest (conversion/csv-column-keys training-set-path)) ;; Disregard the first column which is :label
        maps (conversion/csv-to-maps training-set-path)
        groups (when (some? groups-path)
                 (load-groups groups-path))
        weights (if (some? weights-path)
                  (load-weights weights-path)
                  (when (some? groups)
                    (vec (repeat (count groups) 1.0))))]
    (cond-> {:features (vec features)
             :feature-maps (mapv #(dissoc % :label) maps)
             :labels (mapv :label maps)}
      (some? weights)
      (assoc :weights weights)

      (some? groups)
      (assoc :groups groups))))

(defn save-csv-files
  "Saves a training set to csv files. Labels and feature maps are written to
  `training-set-path`. The groups and weights are written to `weights-path` and
  `groups-path`, if present."
  [{:keys [features feature-maps labels groups weights] :as _training-set}
   training-set-path weights-path groups-path]
  ;; Write the features and labels
  (->> (map #(assoc %1 :label %2)
            feature-maps labels)
       (conversion/maps-to-csv training-set-path
                               (cons :label features)))
  ;; Write groups
  (when (some? groups)
    (conversion/vector-to-csv groups-path "group" groups))
  ;; Write weights
  (when (some? weights)
    (conversion/vector-to-csv weights-path "weight" weights)))

(defn save-temp-csv-files
  "Saves a training set to temporary csv files. Returns a map containing the path
  of the temporary files created: `training-set-path`, `weights-path` (if
  present) and `groups-path` (if present)."
  [{:keys [groups weights] :as training-set}]
  (let [training-set-path (io-utils/create-temp-csv-path)
        groups-path (when (some? groups)
                      (io-utils/create-temp-csv-path))
        weights-path (when (some? weights)
                       (io-utils/create-temp-csv-path))]
    (save-csv-files training-set training-set-path weights-path groups-path)
    (cond-> {:training-set-path training-set-path}
      (some? groups) (assoc :groups-path groups-path)
      (some? weights) (assoc :weights-path weights-path))))

;; =============================================================================
;; Splitting
;; =============================================================================

(defn- fractions-sum-to-one?
  [fractions]
  (let [sum (apply + fractions)]
    (< (Math/abs (- 1.0 sum)) 1e-8)))

(defn- select-examples
  "Returns a subset a training-set containing only the specified `indices`."
  [{:keys [weights] :as training-set} indices]
  (-> training-set
      (update :feature-maps mapv indices)
      (update :labels mapv indices)
      (cond-> (some? weights)
        (update :weights mapv indices))))

(defn- group->example-indices
  "Returns the examples indices corresponding to the `group-indices`. For
  examples, if `groups` is [2 1 2] and `group-indices` is [2 1], this returns
  the indices of examples in groups 2 and 1, which are [3 4 2]."
  [groups group-indices]
  (let [start-indices (reductions + 0 groups)
        example-indices-per-group (mapv #(range %1 (+ %1 %2))
                                        start-indices
                                        groups)]
    (mapcat example-indices-per-group group-indices)))

(defn- select-groups
  "Returns a subset a training-set containing only the specified `group-indices`,
  which corresponds to indices of groups in the `:groups` vector. For example,
  if the `:groups` vector is `[2 2 2]` and the `group-indices` is `[1 2]`, this
  will return the examples 2 to 5 (both inclusive)."
  [{:keys [weights groups] :as training-set} group-indices]
  (-> training-set
      (dissoc :weights :groups)
      (select-examples (group->example-indices groups group-indices))
      (cond-> (seq weights)
        (assoc :weights (mapv weights group-indices)))
      (assoc :groups (mapv groups group-indices))))

(defn- indices-of-splits
  "Given `n` elements to separate across splits defined by `fractions`, returns
  sequences of indices indicating which elements should be in each split.
  `fractions` is a one-sum vector whose length determine the number of splits
  and whose elements tell what fraction of `n` is in each split. `shuffle?`
  indicate whether or not to shuffle the indices before assigning them to
  splits.

  For example, if `n` is 10, `shuffle?` is false and `splits` is [0.5 0.5],
  returns `[[0 1 2 3 4] [5 6 7 8 9 10]]`."
  [n shuffle? fractions]
  (loop [splits []
         indices (-> (range n)
                     (cond-> shuffle?
                       (shuffle)))
         split-sizes (map #(int (Math/ceil (* n %))) fractions)]
    (if-let [size (first split-sizes)]
      (let [[new-split others] (split-at size indices)]
        (recur (conj splits new-split) others (rest split-sizes)))
      splits)))

(defn split
  "Splits a training set into multiple training sets. `fractions` is a one-sum
  vector whose length determine the number of training-sets to create and whose
  elements tell what fraction of the training-set to distribute is in each
  split.

  Training sets having a group vector are split across group, while
  training sets without group vectors are split across examples. `shuffle?`
  indicate whether or not to shuffle examples (or groups) of the training set."
  [{:keys [feature-maps groups] :as training-set} shuffle? fractions]
  {:pre [(fractions-sum-to-one? fractions)]}
  (if (some? groups)
    (map #(select-groups training-set %)
         (indices-of-splits (count groups) shuffle? fractions))
    (map #(select-examples training-set %)
         (indices-of-splits (count feature-maps) shuffle? fractions))))

(defn- concat-training-sets
  [& ts]
  (let [features (:features (first ts))]
    (-> (apply merge-with into ts)
        (assoc :features features))))

(defn k-fold-split
  "Returns a sequence of `k` tuples where the first element is the training data
  and the second the validation data.
  See https://en.wikipedia.org/wiki/Cross-validation_(statistics)"
  [training-set shuffle? k]
  (let [splits (split training-set shuffle? (repeat k (/ 1 k)))]
    (for [i (range k)]
      (let [[valid-split & train-splits] (take k (drop i (cycle splits)))]
        [(apply concat-training-sets train-splits)
         valid-split]))))

(defn train-test-split
  "Splits the `training-set` in two part for training and validation. The former
  contains `train-percent` of the data, while the latter contains the rest"
  [training-set shuffle? train-percent]
  (split training-set shuffle?
         [(/ train-percent 100)
          (/ (- 100 train-percent) 100)]))