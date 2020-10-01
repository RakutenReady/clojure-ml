(ns curbside.ml.models-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [curbside.ml.models :as models]
   [conjure.core :refer [stubbing verify-call-times-for verify-first-call-args-for]]
   [curbside.ml.utils.tests :as tutils]
   [curbside.ml.training-sets.conversion :as conversion]))

(def grid-search-combos-stub-value [{:subsample 0.5, :max_depth 5}
                                    {:subsample 0.5, :max_depth 6}
                                    {:subsample 0.5, :max_depth 7}
                                    {:subsample 0.6, :max_depth 5}
                                    {:subsample 0.6, :max_depth 6}
                                    {:subsample 0.6, :max_depth 7}])

(def random-search-combos-stub-value [{:subsample 0.5643362797872074, :max_depth 6, :booster "dart"}
                                      {:subsample 0.5307578644935428, :max_depth 6, :booster "dart"}
                                      {:subsample 0.9486528438903652, :max_depth 6, :booster "dart"}
                                      {:subsample 0.7317135931408416, :max_depth 8, :booster "dart"}
                                      {:subsample 0.8114551550463982, :max_depth 5, :booster "dart"}
                                      {:subsample 0.5224498589316126, :max_depth 8, :booster "dart"}
                                      {:subsample 0.9091339560549907, :max_depth 5, :booster "dart"}
                                      {:subsample 0.6190130901825939, :max_depth 8, :booster "dart"}
                                      {:subsample 0.8268034625457685, :max_depth 7, :booster "dart"}
                                      {:subsample 0.8190881875862341, :max_depth 5, :booster "dart"}])

(def hyperparameter-search-space-random {:subsample {:min  0.5 :max  0.99 :type "decimal"}
                                         :max_depth {:min  5 :max  9 :type "integer"}
                                         :booster   {:type   "string" :values ["dart"]}})

(deftest test-optimize-hyperparameters-grid
  (testing "Check if optimize hyperparameters returns a model with all valid sets of hyperparameters according to given spec or not for grid search"
    (tutils/stubbing-private [models/grid-search-combos grid-search-combos-stub-value]
      (let [hyperparameters {:eval_metric "mae" :booster "dart"}
            hyperparameter-search-space-grid {:subsample [0.5 0.6] :max_depth [5 6 7]}
            hyperparameter-search-fn {:type :grid}
            evaluate-options {:type :k-fold :folds 2}
            {:keys [optimal-params model-evaluations]} (models/optimize-hyperparameters :xgboost
                                                                                        :regression
                                                                                        ["lat" "lng"]
                                                                                        hyperparameters
                                                                                        hyperparameter-search-fn
                                                                                        hyperparameter-search-space-grid
                                                                                        tutils/dummy-regression-single-label-training-set-path
                                                                                        evaluate-options)]
        (verify-call-times-for models/grid-search-combos 1)
        (verify-first-call-args-for models/grid-search-combos hyperparameter-search-space-grid)
        (is (tutils/approx= 0.6 (:subsample optimal-params) 1e-1))
        (is (= {:subsample 0.6, :max_depth 7 :booster "dart" :eval_metric "mae"} optimal-params))
        (is (tutils/approx= 0.04606 (:mean-absolute-error model-evaluations) 1e-4))
        (is (tutils/approx= 0.04606 (:root-mean-square-error model-evaluations) 1e-4))))))

(deftest test-optimize-hyperparameters-random
  (testing "Check if optimize hyperparameters returns a model with all valid sets of hyperparameters according to given spec or not"
    (tutils/stubbing-private [models/random-search-combos random-search-combos-stub-value]
      (let [hyperparameters {:eval_metric "mae" :booster "dart"}
            hyperparameter-search-fn {:type :random :iteration-count 10}
            evaluate-options {:type :k-fold :folds 2}
            {:keys [optimal-params model-evaluations]} (models/optimize-hyperparameters :xgboost
                                                                                        :regression
                                                                                        ["lat" "lng"]
                                                                                        hyperparameters
                                                                                        hyperparameter-search-fn
                                                                                        hyperparameter-search-space-random
                                                                                        tutils/dummy-regression-single-label-training-set-path
                                                                                        evaluate-options)]
        (verify-call-times-for models/random-search-combos 1)
        (verify-first-call-args-for models/random-search-combos 10 hyperparameter-search-space-random)
        (is (tutils/approx= 0.9486 (:subsample optimal-params) 1e-4))
        (is (= {:subsample 0.9486528438903652 :max_depth 6 :booster "dart" :eval_metric "mae"} optimal-params))
        (is (tutils/approx= 0.0277 (:mean-absolute-error model-evaluations) 1e-4))
        (is (tutils/approx= 0.0277 (:root-mean-square-error model-evaluations) 1e-4))))))

(deftest test-optimize-train-test-validation-split
  (testing "Check if train-test-split validation works properly, and generates models"
    (with-redefs [shuffle identity]
      (tutils/stubbing-private [models/random-search-combos random-search-combos-stub-value]
        (let [hyperparameters {:eval_metric "mae" :booster "dart"}
              hyperparameter-search-fn {:type :random :iteration-count 10}
              evaluate-options {:type :train-test-split :train-split-percentage 80}
              {:keys [optimal-params model-evaluations]} (models/optimize-hyperparameters :xgboost
                                                                                          :regression
                                                                                          ["lat" "lng"]
                                                                                          hyperparameters
                                                                                          hyperparameter-search-fn
                                                                                          hyperparameter-search-space-random
                                                                                          tutils/dummy-regression-single-label-training-set-path
                                                                                          evaluate-options)]
          (verify-call-times-for models/random-search-combos 1)
          (verify-first-call-args-for models/random-search-combos 10 hyperparameter-search-space-random)
          (is (tutils/approx= 0.9486 (:subsample optimal-params) 1e-4))
          (is (= {:subsample 0.9486528438903652 :max_depth 6 :booster "dart" :eval_metric "mae"} optimal-params))
          (is (tutils/approx= 0.0222 (:mean-absolute-error model-evaluations) 1e-4))
          (is (tutils/approx= 0.0222 (:root-mean-square-error model-evaluations) 1e-4)))))))


(deftest test-optimize-ranking
  (testing "given a dummy ranking dataset, we can train a ranking model"
    (with-redefs [shuffle identity]
      (tutils/stubbing-private [models/random-search-combos random-search-combos-stub-value]
        (let [hyperparameters {:num-rounds 5 :max_depth 5 :learning_rate 0.99 :objective "rank:ndcg"}
              hyperparameter-search-fn {:type :random :iteration-count 10}
              evaluate-options {:type :train-test-split :train-split-percentage 80}
              {:keys [optimal-params model-evaluations]} (models/optimize-hyperparameters
                                                          :xgboost
                                                          :ranking
                                                          ["lat" "lng"]
                                                          hyperparameters
                                                          hyperparameter-search-fn
                                                          hyperparameter-search-space-random
                                                          tutils/dummy-ranking-training-set-path
                                                          evaluate-options
                                                          :example-groups-path
                                                          tutils/dummy-ranking-training-set-groups-path)]
          (verify-call-times-for models/random-search-combos 1)
          (verify-first-call-args-for models/random-search-combos 10 hyperparameter-search-space-random)
          (is (= {:ndcg-2 1.0
                  :ndcg-5 1.0
                  :precision-2 0.5
                  :precision-5 0.5
                  :personalization-2 0.0
                  :personalization-5 0.6} model-evaluations))
          (is (= {:num-rounds 5
                  :max_depth 5
                  :learning_rate 0.99
                  :objective "rank:ndcg"
                  :subsample 0.8190881875862341
                  :booster "dart"}
                optimal-params)))))))
