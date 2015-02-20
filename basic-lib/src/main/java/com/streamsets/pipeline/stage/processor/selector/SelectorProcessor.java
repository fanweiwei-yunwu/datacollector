/**
 * (c) 2014 StreamSets, Inc. All rights reserved. May not
 * be copied, modified, or distributed in whole or part without
 * written consent of StreamSets, Inc.
 */
package com.streamsets.pipeline.stage.processor.selector;

import com.streamsets.pipeline.api.BatchMaker;
import com.streamsets.pipeline.api.ChooserMode;
import com.streamsets.pipeline.api.ConfigDef;
import com.streamsets.pipeline.api.ConfigGroups;
import com.streamsets.pipeline.api.Field;
import com.streamsets.pipeline.api.GenerateResourceBundle;
import com.streamsets.pipeline.api.LanePredicateMapping;
import com.streamsets.pipeline.api.Record;
import com.streamsets.pipeline.api.StageDef;
import com.streamsets.pipeline.api.StageException;
import com.streamsets.pipeline.api.ValueChooser;
import com.streamsets.pipeline.api.base.RecordProcessor;
import com.streamsets.pipeline.api.OnRecordError;
import com.streamsets.pipeline.api.OnRecordErrorChooserValues;
import com.streamsets.pipeline.el.ELEvaluator;
import com.streamsets.pipeline.el.ELRecordSupport;
import com.streamsets.pipeline.el.ELStringSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.jsp.el.ELException;
import java.util.List;
import java.util.Map;
import java.util.Set;

@GenerateResourceBundle
@StageDef(
    version = "1.0.0",
    label = "Stream Selector",
    description = "Passes records to streams based on conditions",
    icon="laneSelector.png",
    outputStreams = StageDef.VariableOutputStreams.class,
    outputStreamsDrivenByConfig = "lanePredicates")
@ConfigGroups(com.streamsets.pipeline.stage.processor.selector.ConfigGroups.class)
public class SelectorProcessor extends RecordProcessor {
  private static final Logger LOG = LoggerFactory.getLogger(SelectorProcessor.class);

  @ConfigDef(
      required = true,
      type = ConfigDef.Type.MODEL,
      label = "Condition",
      description = "Records that match the condition pass to the stream",
      displayPosition = 10,
      group = "CONDITIONS"
  )
  @LanePredicateMapping
  public List<Map<String, String>> lanePredicates;

  @ConfigDef(
      required = true,
      type = ConfigDef.Type.MAP,
      label = "Constants",
      description = "Can be used in any expression in the processor",
      displayPosition = 20,
      group = "CONDITIONS"
  )
  public Map<String, ?> constants;

  @ConfigDef(
      required = true,
      type = ConfigDef.Type.MODEL,
      defaultValue = "DISCARD",
      label = "Unmatched Record Handling",
      description = "Action for records without matching conditions",
      displayPosition = 30,
      group = "CONDITIONS"
  )
  @ValueChooser(type = ChooserMode.PROVIDED, chooserValues = OnRecordErrorChooserValues.class)
  public OnRecordError onNoPredicateMatch;

  private String[][] predicateLanes;
  private ELEvaluator elEvaluator;
  private ELEvaluator.Variables variables;

  private String[][] parsePredicateLanes(List<Map<String, String>> predicateLanesList) throws StageException {
    String[][] predicateLanes = new String[predicateLanesList.size()][];
    int count = 0;
    for (Map<String, String> predicateLaneMap : predicateLanesList) {
      String outputLane = predicateLaneMap.get("outputLane");
      Object predicate = predicateLaneMap.get("predicate");
      if (!getContext().getOutputLanes().contains(outputLane)) {
        throw new StageException(Errors.SELECTOR_02, outputLane, predicate);
      }
      predicateLanes[count] = new String[2];
      predicateLanes[count][0] = (String) predicate;
      predicateLanes[count][1] = outputLane;
      LOG.debug("Condition:'{}' Stream:'{}'", predicate, outputLane);
      count++;
    }
    return predicateLanes;
  }

  @SuppressWarnings("unchecked")
  private ELEvaluator.Variables parseConstants(Map<String,?> constants) throws StageException {
    ELEvaluator.Variables variables = new ELEvaluator.Variables();
    if (constants != null) {
      for (Map.Entry<String, ?> entry : constants.entrySet()) {
        variables.addVariable(entry.getKey(), entry.getValue());
        LOG.debug("Variable: {}='{}'", entry.getKey(), entry.getValue());
      }
    }
    return variables;
  }

  @Override
  protected void init() throws StageException {
    super.init();
    if (lanePredicates == null || lanePredicates.size() == 0) {
      throw new StageException(Errors.SELECTOR_00);
    }
    if (getContext().getOutputLanes().size() != lanePredicates.size()) {
      throw new StageException(Errors.SELECTOR_01, getContext().getOutputLanes(), lanePredicates.size());
    }
    predicateLanes = parsePredicateLanes(lanePredicates);
    variables = parseConstants(constants);
    elEvaluator = new ELEvaluator();
    ELRecordSupport.registerRecordFunctions(elEvaluator);
    ELStringSupport.registerStringFunctions(elEvaluator);
    validateELs();
    LOG.debug("All conditions validated");
  }

  private void validateELs() throws StageException {

    Record record = new Record(){
      @Override
      public Header getHeader() {
        return null;
      }

      @Override
      public Field get() {
        return null;
      }

      @Override
      public Field set(Field field) {
        return null;
      }

      @Override
      public Field get(String fieldPath) {
        return null;
      }

      @Override
      public Field delete(String fieldPath) {
        return null;
      }

      @Override
      public boolean has(String fieldPath) {
        return false;
      }

      @Override
      public Set<String> getFieldPaths() {
        return null;
      }

      @Override
      public Field set(String fieldPath, Field newField) {
        return null;
      }

    };

    variables.addVariable("default", false);
    ELRecordSupport.setRecordInContext(variables, record);
    for (String[] predicateLane : predicateLanes) {
      try {
        elEvaluator.eval(variables, predicateLane[0], Boolean.class);
      } catch (ELException ex) {
        throw new StageException(Errors.SELECTOR_03, predicateLane[0], ex.getMessage(), ex);
      }
    }
  }

  @Override
  protected void process(Record record, BatchMaker batchMaker) throws StageException {
    boolean matchedAtLeastOnePredicate = false;
    ELRecordSupport.setRecordInContext(variables, record);
    for (String[] pl : predicateLanes) {
      variables.addVariable("default", !matchedAtLeastOnePredicate);
      try {
        if (elEvaluator.eval(variables, pl[0], Boolean.class)) {
          LOG.trace("Record '{}' satisfies condition '{}', going to stream '{}'", record.getHeader().getSourceId(),
                    pl[0], pl[1]);
          batchMaker.addRecord(record, pl[1]);
          matchedAtLeastOnePredicate = true;
        } else{
          LOG.trace("Record '{}' does not satisfy condition '{}', skipping stream '{}'", record.getHeader().getSourceId(),
                    pl[0], pl[1]);
        }
      } catch (ELException ex) {
        getContext().toError(record, Errors.SELECTOR_04, pl[0], ex.getMessage(), ex);
      }
    }
    if (!matchedAtLeastOnePredicate) {
      switch (onNoPredicateMatch) {
        case DISCARD:
          LOG.trace("Record '{}' does not satisfy any condition, dropping it", record.getHeader().getSourceId());
          break;
        case TO_ERROR:
          LOG.trace("Record '{}' does not satisfy any condition, sending it to error",
                    record.getHeader().getSourceId());
          getContext().toError(record, Errors.SELECTOR_05);
          break;
        case STOP_PIPELINE:
          LOG.error(Errors.SELECTOR_06.getMessage(), record.getHeader().getSourceId());
          throw new StageException(Errors.SELECTOR_06, record.getHeader().getSourceId());
      }
    }
  }

}
