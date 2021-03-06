(ns metabase.task.sync-databases-test
  "Tests for the logic behind scheduling the various sync operations of Databases. Most of the actual logic we're testing is part of `metabase.models.database`,
   so there's an argument to be made that these sorts of tests could just as easily belong to a `database-test` namespace."
  (:require [clojure.string :as str]
            [expectations :refer :all]
            [metabase.models.database :refer [Database]]
            metabase.task.sync-databases
            [metabase.test.util :as tu]
            [metabase.util :as u]
            [toucan.db :as db]
            [toucan.util.test :as tt])
  (:import [metabase.task.sync_databases SyncAndAnalyzeDatabase UpdateFieldValues]))

(defn- replace-trailing-id-with-<id> [s]
  (str/replace s #"\d+$" "<id>"))

(defn- replace-ids-with-<id> [current-tasks]
  (vec (for [task current-tasks]
         (-> task
             (update :description replace-trailing-id-with-<id>)
             (update :key replace-trailing-id-with-<id>)
             (update-in [:data "db-id"] class)
             (update :triggers (fn [triggers]
                                 (vec (for [trigger triggers]
                                        (update trigger :key replace-trailing-id-with-<id>)))))))))

(defn- current-tasks []
  (replace-ids-with-<id> (tu/scheduler-current-tasks)))

;; Check that a newly created database automatically gets scheduled
(expect
  [{:description "sync-and-analyze Database <id>"
    :class       SyncAndAnalyzeDatabase
    :key         "metabase.task.sync-and-analyze.job.<id>"
    :data        {"db-id" Integer}
    :triggers    [{:key           "metabase.task.sync-and-analyze.trigger.<id>"
                   :cron-schedule "0 50 * * * ? *"}]}
   {:description "update-field-values Database <id>"
    :class       UpdateFieldValues
    :key         "metabase.task.update-field-values.job.<id>"
    :data        {"db-id" Integer}
    :triggers    [{:key           "metabase.task.update-field-values.trigger.<id>"
                   :cron-schedule "0 50 0 * * ? *"}]}]
  (tu/with-temp-scheduler
    (tt/with-temp Database [database {:engine :postgres}]
      (current-tasks))))


;; Check that a custom schedule is respected when creating a new Database
(expect
  [{:description "sync-and-analyze Database <id>"
    :class       SyncAndAnalyzeDatabase
    :key         "metabase.task.sync-and-analyze.job.<id>"
    :data        {"db-id" Integer}
    :triggers    [{:key           "metabase.task.sync-and-analyze.trigger.<id>"
                   :cron-schedule "0 30 4,16 * * ? *"}]}
   {:description "update-field-values Database <id>"
    :class       UpdateFieldValues
    :key         "metabase.task.update-field-values.job.<id>"
    :data        {"db-id" Integer}
    :triggers    [{:key           "metabase.task.update-field-values.trigger.<id>"
                   :cron-schedule "0 15 10 ? * 6#3"}]}]
  (tu/with-temp-scheduler
    (tt/with-temp Database [database {:engine                      :postgres
                                      :metadata_sync_schedule      "0 30 4,16 * * ? *" ; 4:30 AM and PM daily
                                      :cache_field_values_schedule "0 15 10 ? * 6#3"}] ; 10:15 on the 3rd Friday of the Month
      (current-tasks))))


;; Check that a deleted database gets unscheduled
(expect
  []
  (tu/with-temp-scheduler
    (tt/with-temp Database [database {:engine :postgres}]
      (db/delete! Database :id (u/get-id database))
      (current-tasks))))

;; Check that changing the schedule column(s) for a DB properly updates the scheduled tasks
(expect
  [{:description "sync-and-analyze Database <id>"
    :class       SyncAndAnalyzeDatabase
    :key         "metabase.task.sync-and-analyze.job.<id>"
    :data        {"db-id" Integer}
    :triggers    [{:key           "metabase.task.sync-and-analyze.trigger.<id>"
                   :cron-schedule "0 15 10 ? * MON-FRI"}]}
   {:description "update-field-values Database <id>"
    :class       UpdateFieldValues
    :key         "metabase.task.update-field-values.job.<id>"
    :data        {"db-id" Integer}
    :triggers    [{:key           "metabase.task.update-field-values.trigger.<id>"
                   :cron-schedule "0 11 11 11 11 ?"}]}]
  (tu/with-temp-scheduler
    (tt/with-temp Database [database {:engine :postgres}]
      (db/update! Database (u/get-id database)
        :metadata_sync_schedule      "0 15 10 ? * MON-FRI" ; 10:15 AM every weekday
        :cache_field_values_schedule "0 11 11 11 11 ?") ; Every November 11th at 11:11 AM
      (current-tasks))))

;; Check that changing one schedule doesn't affect the other
(expect
  [{:description "sync-and-analyze Database <id>"
    :class       SyncAndAnalyzeDatabase
    :key         "metabase.task.sync-and-analyze.job.<id>"
    :data        {"db-id" Integer}
    :triggers    [{:key           "metabase.task.sync-and-analyze.trigger.<id>"
                   :cron-schedule "0 50 * * * ? *"}]}
   {:description "update-field-values Database <id>"
    :class       UpdateFieldValues
    :key         "metabase.task.update-field-values.job.<id>"
    :data        {"db-id" Integer}
    :triggers    [{:key           "metabase.task.update-field-values.trigger.<id>"
                   :cron-schedule "0 15 10 ? * MON-FRI"}]}]
  (tu/with-temp-scheduler
    (tt/with-temp Database [database {:engine :postgres}]
      (db/update! Database (u/get-id database)
        :cache_field_values_schedule "0 15 10 ? * MON-FRI")
      (current-tasks))))

(expect
  [{:description "sync-and-analyze Database <id>"
    :class       SyncAndAnalyzeDatabase
    :key         "metabase.task.sync-and-analyze.job.<id>"
    :data        {"db-id" Integer}
    :triggers    [{:key           "metabase.task.sync-and-analyze.trigger.<id>"
                   :cron-schedule "0 15 10 ? * MON-FRI"}]}
   {:description "update-field-values Database <id>"
    :class       UpdateFieldValues
    :key         "metabase.task.update-field-values.job.<id>"
    :data        {"db-id" Integer}
    :triggers    [{:key           "metabase.task.update-field-values.trigger.<id>"
                   :cron-schedule "0 50 0 * * ? *"}]}]
  (tu/with-temp-scheduler
    (tt/with-temp Database [database {:engine :postgres}]
      (db/update! Database (u/get-id database)
        :metadata_sync_schedule "0 15 10 ? * MON-FRI")
      (current-tasks))))

;; Check that you can't INSERT a DB with an invalid schedule
(expect
  Exception
  (db/insert! Database {:engine                 :postgres
                        :metadata_sync_schedule "0 * ABCD"}))

(expect
  Exception
  (db/insert! Database {:engine                      :postgres
                        :cache_field_values_schedule "0 * ABCD"}))

;; Check that you can't UPDATE a DB's schedule to something invalid
(expect
  Exception
  (tt/with-temp Database [database {:engine :postgres}]
    (db/update! Database (u/get-id database)
      :metadata_sync_schedule "2 CANS PER DAY")))

(expect
  Exception
  (tt/with-temp Database [database {:engine :postgres}]
    (db/update! Database (u/get-id database)
      :cache_field_values_schedule "2 CANS PER DAY")))


;;; +------------------------------------------------------------------------------------------------------------------------+
;;; |                                        CHECKING THAT SYNC TASKS RUN CORRECT FNS                                        |
;;; +------------------------------------------------------------------------------------------------------------------------+

(defn- check-if-sync-processes-ran-for-db {:style/indent 0} [db-info]
  (let [sync-db-metadata-counter    (atom 0)
        analyze-db-counter          (atom 0)
        update-field-values-counter (atom 0)]
    (with-redefs [metabase.sync.sync-metadata/sync-db-metadata!   (fn [& _] (swap! sync-db-metadata-counter inc))
                  metabase.sync.analyze/analyze-db!               (fn [& _] (swap! analyze-db-counter inc))
                  metabase.sync.field-values/update-field-values! (fn [& _] (swap! update-field-values-counter inc))]
      (tu/with-temp-scheduler
        (tt/with-temp Database [database db-info]
          ;; give tasks some time to run
          (Thread/sleep 2000)
          {:ran-sync?                (not (zero? @sync-db-metadata-counter))
           :ran-analyze?             (not (zero? @analyze-db-counter))
           :ran-update-field-values? (not (zero? @update-field-values-counter))})))))

(defn- cron-schedule-for-next-year []
  (format "0 15 10 * * ? %d" (inc (u/date-extract :year))))

;; Make sure that a database that *is* marked full sync *will* get analyzed
(expect
  {:ran-sync? true, :ran-analyze? true, :ran-update-field-values? false}
  (check-if-sync-processes-ran-for-db
    {:engine                      :postgres
     :metadata_sync_schedule      "* * * * * ? *"
     :cache_field_values_schedule (cron-schedule-for-next-year)}))

;; Make sure that a database that *isn't* marked full sync won't get analyzed
(expect
  {:ran-sync? true, :ran-analyze? false, :ran-update-field-values? false}
  (check-if-sync-processes-ran-for-db
    {:engine                      :postgres
     :is_full_sync                false
     :metadata_sync_schedule      "* * * * * ? *"
     :cache_field_values_schedule (cron-schedule-for-next-year)}))

;; Make sure the update field values task calls `update-field-values!`
(expect
  {:ran-sync? false, :ran-analyze? false, :ran-update-field-values? true}
  (check-if-sync-processes-ran-for-db
    {:engine                      :postgres
     :is_full_sync                false
     :metadata_sync_schedule      (cron-schedule-for-next-year)
     :cache_field_values_schedule "* * * * * ? *"}))
