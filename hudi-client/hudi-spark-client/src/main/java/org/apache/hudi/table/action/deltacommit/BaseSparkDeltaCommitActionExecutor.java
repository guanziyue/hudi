/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hudi.table.action.deltacommit;

import org.apache.hudi.client.WriteStatus;
import org.apache.hudi.client.common.HoodieSparkEngineContext;
import org.apache.hudi.common.data.HoodieData;
import org.apache.hudi.common.fs.FSUtils;
import org.apache.hudi.common.model.HoodieCommitMetadata;
import org.apache.hudi.common.model.HoodieDeltaWriteStat;
import org.apache.hudi.common.model.HoodieLogFile;
import org.apache.hudi.common.model.HoodieRecord;
import org.apache.hudi.common.model.HoodieWriteStat;
import org.apache.hudi.common.model.WriteOperationType;
import org.apache.hudi.common.util.Option;
import org.apache.hudi.config.HoodieWriteConfig;
import org.apache.hudi.exception.HoodieIOException;
import org.apache.hudi.exception.HoodieUpsertException;
import org.apache.hudi.execution.SparkLazyInsertIterable;
import org.apache.hudi.io.AppendHandleFactory;
import org.apache.hudi.io.HoodieAppendHandle;
import org.apache.hudi.table.HoodieTable;
import org.apache.hudi.table.WorkloadProfile;
import org.apache.hudi.table.action.HoodieWriteMetadata;
import org.apache.hudi.table.action.commit.BaseSparkCommitActionExecutor;
import org.apache.hudi.table.marker.WriteMarkers;
import org.apache.hudi.table.marker.WriteMarkersFactory;

import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.spark.Partitioner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

public abstract class BaseSparkDeltaCommitActionExecutor<T>
    extends BaseSparkCommitActionExecutor<T> {
  private static final Logger LOG = LoggerFactory.getLogger(BaseSparkDeltaCommitActionExecutor.class);

  // UpsertPartitioner for MergeOnRead table type
  private SparkUpsertDeltaCommitPartitioner<T> mergeOnReadUpsertPartitioner;

  public BaseSparkDeltaCommitActionExecutor(HoodieSparkEngineContext context, HoodieWriteConfig config, HoodieTable table,
                                                String instantTime, WriteOperationType operationType) {
    this(context, config, table, instantTime, operationType, Option.empty());
  }

  public BaseSparkDeltaCommitActionExecutor(HoodieSparkEngineContext context, HoodieWriteConfig config, HoodieTable table,
                                                String instantTime, WriteOperationType operationType,
                                                Option<Map<String, String>> extraMetadata) {
    super(context, config, table, instantTime, operationType, extraMetadata);
  }

  protected HoodieCommitMetadata appendMetadataForMissingFiles(HoodieCommitMetadata metadata) {
    WriteMarkers markers = WriteMarkersFactory.get(config.getMarkersType(), table, instantTime);
    // if there is log files in this delta commit, we search any invalid log files generated by failed spark task
    boolean hasLogFileInDeltaCommit = metadata.getPartitionToWriteStats()
        .values().stream().flatMap(List::stream)
        .anyMatch(writeStat -> FSUtils.isLogFile(new Path(config.getBasePath(), writeStat.getPath()).getName()));
    if (hasLogFileInDeltaCommit) {
      // get all log files generated by log mark file
      Set<String> logFilesMarkerPath = null;
      try {
        logFilesMarkerPath = new HashSet<>(markers.appendedLogPaths(context, config.getFinalizeWriteParallelism()));
      } catch (IOException e) {
        throw new HoodieIOException("Failed to get log file markers", e);
      }

      // remove valid log files
      for (Map.Entry<String, List<HoodieWriteStat>> partitionAndWriteStats : metadata.getPartitionToWriteStats().entrySet()) {
        for (HoodieWriteStat hoodieWriteStat : partitionAndWriteStats.getValue()) {
          logFilesMarkerPath.remove(hoodieWriteStat.getPath());
        }
      }

      // remaining are invalid log files, let's generate write stat for them
      if (logFilesMarkerPath.size() > 0) {
        context.setJobStatus(this.getClass().getSimpleName(), "generate writeStat for missing log files");
        List<Option<HoodieDeltaWriteStat>> additionalLogFileWriteStat = context.map(new ArrayList<>(logFilesMarkerPath), (logFilePath) -> {
          FileSystem fileSystem = table.getMetaClient().getFs();
          FileStatus fileStatus;
          try {
            fileStatus = fileSystem.getFileStatus(new Path(config.getBasePath(), logFilePath));
          } catch (FileNotFoundException fileNotFoundException) {
            return Option.empty();
          }

          HoodieDeltaWriteStat writeStat = new HoodieDeltaWriteStat();
          HoodieLogFile logFile = new HoodieLogFile(fileStatus);
          writeStat.setPath(logFilePath);
          writeStat.setFileId(logFile.getFileId());
          writeStat.setFileSizeInBytes(logFile.getFileSize());
          writeStat.setPartitionPath(FSUtils.getRelativePartitionPath(new Path(config.getBasePath()), fileStatus.getPath().getParent()));
          return Option.of(writeStat);
        }, config.getFinalizeWriteParallelism());

        // These log files found by marker file are generated by failed spark task. So they don't have HoodieWriteStat. Let's add these write stat to commit meta.
        for (Option<HoodieDeltaWriteStat> deltaWriteStat : additionalLogFileWriteStat) {
          deltaWriteStat.ifPresent(d -> metadata.addWriteStat(d.getPartitionPath(), d));
        }
      }
    }
    return metadata;
  }

  /* In spark mor table, any failed spark task may generate log files which are not included in write status.
   * We need to add these to CommitMetadata so that it will be synced to MDT and make MDT has correct file info.
   */
  private HoodieCommitMetadata addMissingLogFileIfNeeded(HoodieWriteMetadata<HoodieData<WriteStatus>> result) throws IOException {
    HoodieCommitMetadata metadata = result.getCommitMetadata().get();
    WriteMarkers markers = WriteMarkersFactory.get(config.getMarkersType(), table, instantTime);
    // if there is log files in this delta commit, we search any invalid log files generated by failed spark task
    boolean hasLogFileInDeltaCommit = metadata.getPartitionToWriteStats()
        .values().stream().flatMap(List::stream)
        .anyMatch(writeStat -> FSUtils.isLogFile(new Path(config.getBasePath(), writeStat.getPath()).getName()));
    if (hasLogFileInDeltaCommit) {
      // get all log files generated by log mark file
      Set<String> logFilesMarkerPath = new HashSet<>(markers.appendedLogPaths(context, config.getFinalizeWriteParallelism()));

      // remove valid log files
      for (Map.Entry<String, List<HoodieWriteStat>> partitionAndWriteStats : metadata.getPartitionToWriteStats().entrySet()) {
        for (HoodieWriteStat hoodieWriteStat : partitionAndWriteStats.getValue()) {
          logFilesMarkerPath.remove(hoodieWriteStat.getPath());
        }
      }

      // remaining are invalid log files, let's generate write stat for them
      if (logFilesMarkerPath.size() > 0) {
        context.setJobStatus(this.getClass().getSimpleName(), "generate writeStat for missing log files");
        List<Option<HoodieDeltaWriteStat>> additionalLogFileWriteStat = context.map(new ArrayList<>(logFilesMarkerPath), (logFilePath) -> {
          FileSystem fileSystem = table.getMetaClient().getFs();
          FileStatus fileStatus;
          try {
            fileStatus = fileSystem.getFileStatus(new Path(config.getBasePath(), logFilePath));
          } catch (FileNotFoundException fileNotFoundException) {
            return Option.empty();
          }

          HoodieDeltaWriteStat writeStat = new HoodieDeltaWriteStat();
          HoodieLogFile logFile = new HoodieLogFile(fileStatus);
          writeStat.setPath(logFilePath);
          writeStat.setFileId(logFile.getFileId());
          writeStat.setFileSizeInBytes(logFile.getFileSize());
          writeStat.setPartitionPath(FSUtils.getRelativePartitionPath(new Path(config.getBasePath()), fileStatus.getPath().getParent()));
          return Option.of(writeStat);
        }, config.getFinalizeWriteParallelism());

        // These log files found by marker file are generated by failed spark task. So they don't have HoodieWriteStat. Let's add these write stat to commit meta.
        for (Option<HoodieDeltaWriteStat> deltaWriteStat : additionalLogFileWriteStat) {
          deltaWriteStat.ifPresent(d -> metadata.addWriteStat(d.getPartitionPath(), d));
        }
      }
    }
    return metadata;
  }

  @Override
  public Partitioner getUpsertPartitioner(WorkloadProfile profile) {
    if (profile == null) {
      throw new HoodieUpsertException("Need workload profile to construct the upsert partitioner.");
    }
    mergeOnReadUpsertPartitioner = new SparkUpsertDeltaCommitPartitioner<>(profile, (HoodieSparkEngineContext) context, table, config);
    return mergeOnReadUpsertPartitioner;
  }

  @Override
  public Iterator<List<WriteStatus>> handleUpdate(String partitionPath, String fileId,
      Iterator<HoodieRecord<T>> recordItr) throws IOException {
    LOG.info("Merging updates for commit " + instantTime + " for file " + fileId);
    if (!table.getIndex().canIndexLogFiles() && mergeOnReadUpsertPartitioner != null
        && mergeOnReadUpsertPartitioner.getSmallFileIds().contains(fileId)) {
      LOG.info("Small file corrections for updates for commit " + instantTime + " for file " + fileId);
      return super.handleUpdate(partitionPath, fileId, recordItr);
    } else {
      HoodieAppendHandle<?, ?, ?, ?> appendHandle = new HoodieAppendHandle<>(config, instantTime, table,
          partitionPath, fileId, recordItr, taskContextSupplier);
      appendHandle.doAppend();
      return Collections.singletonList(appendHandle.close()).iterator();
    }
  }

  @Override
  public Iterator<List<WriteStatus>> handleInsert(String idPfx, Iterator<HoodieRecord<T>> recordItr) {
    // If canIndexLogFiles, write inserts to log files else write inserts to base files
    if (table.getIndex().canIndexLogFiles()) {
      return new SparkLazyInsertIterable<>(recordItr, true, config, instantTime, table,
          idPfx, taskContextSupplier, new AppendHandleFactory<>());
    } else {
      return super.handleInsert(idPfx, recordItr);
    }
  }

}
