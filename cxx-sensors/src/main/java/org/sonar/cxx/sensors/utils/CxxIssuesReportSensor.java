/*
 * Sonar C++ Plugin (Community)
 * Copyright (C) 2010-2019 SonarOpenCommunity
 * http://github.com/SonarOpenCommunity/sonar-cxx
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package org.sonar.cxx.sensors.utils;

import java.io.File;
import java.io.IOException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;
import org.sonar.api.batch.fs.InputFile;
import org.sonar.api.batch.sensor.SensorContext;
import org.sonar.api.batch.sensor.issue.NewIssue;
import org.sonar.api.batch.sensor.issue.NewIssueLocation;
import org.sonar.api.measures.Metric;
import org.sonar.api.rule.RuleKey;
import org.sonar.api.utils.log.Logger;
import org.sonar.api.utils.log.Loggers;
import org.sonar.cxx.CxxLanguage;
import org.sonar.cxx.CxxMetricsFactory;
import org.sonar.cxx.utils.CxxReportIssue;
import org.sonar.cxx.utils.CxxReportLocation;

/**
 * This class is used as base for all sensors which import external reports, which contain issues. It hosts common logic
 * such as saving issues in SonarQube
 */
public abstract class CxxIssuesReportSensor extends CxxReportSensor {

  private static final Logger LOG = Loggers.get(CxxIssuesReportSensor.class);

  private final Set<String> notFoundFiles = new HashSet<>();
  private final Set<CxxReportIssue> uniqueIssues = new HashSet<>();
  private final Map<InputFile, Integer> violationsPerFileCount = new HashMap<>();
  private int violationsPerModuleCount;
  private final String ruleRepositoryKey;

  /**
   * {@inheritDoc}
   */
  protected CxxIssuesReportSensor(CxxLanguage language, String propertiesKeyPathToReports, String ruleRepositoryKey) {
    super(language, propertiesKeyPathToReports);
    this.ruleRepositoryKey = ruleRepositoryKey;
  }

  private static NewIssueLocation createNewIssueLocationModule(SensorContext sensorContext, NewIssue newIssue,
    CxxReportLocation location) {
    return newIssue.newLocation().on(sensorContext.module()).message(location.getInfo());
  }

  public String getRuleRepositoryKey() {
    return ruleRepositoryKey;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void execute(SensorContext context) {
    try {
      LOG.info("Searching reports by relative path with basedir '{}' and search prop '{}'",
        context.fileSystem().baseDir(), getReportPathKey());
      List<File> reports = getReports(context.config(), context.fileSystem().baseDir(), getReportPathKey());
      notFoundFiles.clear();
      violationsPerFileCount.clear();
      violationsPerModuleCount = 0;

      for (File report : reports) {
        int prevViolationsCount = violationsPerModuleCount;
        LOG.info("Processing report '{}'", report);
        executeReport(context, report, prevViolationsCount);
      }

      Metric<Integer> metric = getLanguage().getMetric(this.getMetricKey());
      LOG.info("{} processed = {}", metric.getKey(), violationsPerModuleCount);

      for (Map.Entry<InputFile, Integer> entry : violationsPerFileCount.entrySet()) {
        context.<Integer>newMeasure()
          .forMetric(metric)
          .on(entry.getKey())
          .withValue(entry.getValue())
          .save();
      }

      // this sensor could be executed on module without any files
      // (possible for hierarchical multi-module projects)
      // don't publish 0 as module metric,
      // let AggregateMeasureComputer calculate the correct value
      if (violationsPerModuleCount != 0) {
        context.<Integer>newMeasure()
          .forMetric(metric)
          .on(context.module())
          .withValue(violationsPerModuleCount)
          .save();
      }
    } catch (Exception e) {
      String msg = new StringBuilder(256)
        .append("Cannot feed the data into sonar, details: '")
        .append(CxxUtils.getStackTrace(e))
        .append("'")
        .toString();
      LOG.error(msg);
      CxxUtils.validateRecovery(e, getLanguage());
    }
  }

  /**
   * Saves code violation only if it wasn't already saved
   *
   * @param sensorContext
   * @param issue
   */
  public void saveUniqueViolation(SensorContext sensorContext, CxxReportIssue issue) {
    if (uniqueIssues.add(issue)) {
      saveViolation(sensorContext, issue);
    }
  }

  private InputFile getInputFileTryRealPath(SensorContext sensorContext, String path) {
    final Path absolutePath = sensorContext.fileSystem().baseDir().toPath().resolve(path);
    Path realPath;
    try {
      realPath = absolutePath.toRealPath(LinkOption.NOFOLLOW_LINKS);
    } catch (IOException | RuntimeException e) {
      if (LOG.isDebugEnabled()) {
        LOG.debug("Unable to get the real path: module '{}', baseDir '{}', path '{}', exception '{}'",
            sensorContext.module().key(), sensorContext.fileSystem().baseDir(), path, e.getMessage());
      }
      return null;
    }

    // if the real path is equal to the given one - skip search; we already
    // tried such path
    //
    // IMPORTANT: don't use Path::equals(), since it's dependent on a file-system.
    // SonarQube plugin API works with string paths, so the equality of strings
    // is important
    final String realPathString = realPath.toString();
    if (absolutePath.toString().equals(realPathString)) {
      return null;
    }

    return sensorContext.fileSystem()
        .inputFile(sensorContext.fileSystem().predicates().hasAbsolutePath(realPathString));
  }

  public InputFile getInputFileIfInProject(SensorContext sensorContext, String path) {
    if (notFoundFiles.contains(path)) {
      return null;
    }

    // 1. try the most generic search predicate first; usually it's the right
    // one
    InputFile inputFile = sensorContext.fileSystem()
        .inputFile(sensorContext.fileSystem().predicates().hasPath(path));

    // 2. if there was nothing found, try to normalize the path by means of
    // Path::toRealPath(). This helps if some 3rd party tools obfuscate the
    // paths. E.g. the MS VC compiler tends to transform file paths to the lower
    // case in its logs.
    //
    // IMPORTANT: SQ plugin API allows creation of NewIssue only on locations,
    // which belong to the module. This internal check is performed by means
    // of comparison of the paths. The paths which are managed by the framework
    // (the reference paths) are NOT stored in the canonical form.
    // E.g. the plugin API neither resolves symbolic links nor performs
    // case-insensitive path normalization (could be relevant on Windows)
    //
    // Normalization by means of File::getCanonicalFile() or Path::toRealPath()
    // can produce paths, which don't pass the mentioned check. E.g. resolution
    // of symbolic links or letter case transformation
    // might lead to the paths, which don't belong to the module's base
    // directory (at least not in terms of parent-child semantic). This is the
    // reason why we should avoid the resolution of symbolic links and not use
    // the Path::toRealPath() as the only search predicate.

    if (inputFile == null) {
      inputFile = getInputFileTryRealPath(sensorContext, path);
    }

    if (inputFile == null) {
      LOG.warn("Cannot find the file '{}' in module '{}' base dir '{}', skipping violations.",
        path, sensorContext.module().key(), sensorContext.fileSystem().baseDir());
      notFoundFiles.add(path);
    }
    return inputFile;
  }

  /**
   * @param context
   * @param report
   * @param prevViolationsCount
   * @throws Exception
   */
  private void executeReport(SensorContext context, File report, int prevViolationsCount) throws Exception {
    try {
      processReport(context, report);
      if (LOG.isDebugEnabled()) {
        Metric<Integer> metric = getLanguage().getMetric(this.getMetricKey());
        LOG.debug("{} processed = {}", metric.getKey(),
          violationsPerModuleCount - prevViolationsCount);
      }
    } catch (EmptyReportException e) {
      LOG.warn("The report '{}' seems to be empty, ignoring.", report);
      LOG.debug("Cannot read report", e);
      CxxUtils.validateRecovery(e, getLanguage());
    }
  }

  private NewIssueLocation createNewIssueLocationFile(SensorContext sensorContext, NewIssue newIssue,
    CxxReportLocation location, Set<InputFile> affectedFiles) {
    InputFile inputFile = getInputFileIfInProject(sensorContext, location.getFile());
    if (inputFile != null) {
      int lines = inputFile.lines();
      int lineNr = Integer.max(1, getLineAsInt(location.getLine(), lines));
      NewIssueLocation newIssueLocation = newIssue.newLocation().on(inputFile).at(inputFile.selectLine(lineNr))
        .message(location.getInfo());
      affectedFiles.add(inputFile);
      return newIssueLocation;
    }
    return null;
  }

  /**
   * Saves a code violation which is detected in the given file/line and has given ruleId and message. Saves it to the
   * given project and context. Project or file-level violations can be saved by passing null for the according
   * parameters ('file' = null for project level, 'line' = null for file-level)
   */
  private void saveViolation(SensorContext sensorContext, CxxReportIssue issue) {
    NewIssue newIssue = sensorContext.newIssue().forRule(RuleKey.of(getRuleRepositoryKey(), issue.getRuleId()));

    Set<InputFile> affectedFiles = new HashSet<>();
    List<NewIssueLocation> newIssueLocations = new ArrayList<>();

    for (CxxReportLocation location : issue.getLocations()) {
      if (location.getFile() != null && !location.getFile().isEmpty()) {
        NewIssueLocation newIssueLocation = createNewIssueLocationFile(sensorContext, newIssue, location,
          affectedFiles);
        if (newIssueLocation != null) {
          newIssueLocations.add(newIssueLocation);
        }
      } else {
        NewIssueLocation newIssueLocation = createNewIssueLocationModule(sensorContext, newIssue, location);
        newIssueLocations.add(newIssueLocation);
      }
    }

    if (!newIssueLocations.isEmpty()) {
      try {
        newIssue.at(newIssueLocations.get(0));
        for (int i = 1; i < newIssueLocations.size(); i++) {
          newIssue.addLocation(newIssueLocations.get(i));
        }
        newIssue.save();

        for (InputFile affectedFile : affectedFiles) {
          violationsPerFileCount.merge(affectedFile, 1, Integer::sum);
        }
        violationsPerModuleCount++;
      } catch (RuntimeException ex) {
        LOG.error("Could not add the issue '{}':{}', skipping issue", issue.toString(), CxxUtils.getStackTrace(ex));
        CxxUtils.validateRecovery(ex, getLanguage());
      }
    }
  }

  private int getLineAsInt(@Nullable String line, int maxLine) {
    int lineNr = 0;
    if (line != null) {
      try {
        lineNr = Integer.parseInt(line);
        if (lineNr < 1) {
          lineNr = 1;
        } else if (lineNr > maxLine) { // https://jira.sonarsource.com/browse/SONAR-6792
          lineNr = maxLine;
        }
      } catch (java.lang.NumberFormatException nfe) {
        LOG.warn("Skipping invalid line number: {}", line);
        CxxUtils.validateRecovery(nfe, getLanguage());
        lineNr = -1;
      }
    }
    return lineNr;
  }

  protected abstract void processReport(final SensorContext context, File report) throws Exception;

  protected abstract CxxMetricsFactory.Key getMetricKey();

}
