// Copyright (C) 2003-2009 by Object Mentor, Inc. All rights reserved.
// Released under the terms of the CPL Common Public License version 1.0.
package fitnesse.testsystems.slim.tables;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import fitnesse.slim.SlimError;
import fitnesse.testsystems.ExecutionResult;
import fitnesse.testsystems.TestSummary;
import fitnesse.testsystems.slim.HtmlTableScanner;
import fitnesse.testsystems.slim.SlimTestContext;
import fitnesse.testsystems.slim.Table;
import fitnesse.testsystems.slim.TableScanner;
import fitnesse.testsystems.slim.results.Result;
import util.StringUtil;


public class ScenarioTable extends SlimTable {
  private static final String instancePrefix = "scenarioTable";
  private static final String underscorePattern = "\\W_(?:\\W|$)";
  private String name;
  private List<String> inputs = new ArrayList<String>();
  private Set<String> outputs = new HashSet<String>();
  private final int colsInHeader = table.getColumnCountInRow(0);
  private boolean parameterized = false;

  public ScenarioTable(Table table, String tableId,
                       SlimTestContext testContext) {
    super(table, tableId, testContext);
  }

  protected String getTableType() {
    return instancePrefix;
  }

  public List<Object> getInstructions() throws SyntaxError {
    parseTable();

    // Note: scenario's only add instructions when needed to,
    // since they might need parameters.
    return Collections.emptyList();
  }

  private void parseTable() throws SyntaxError {
    validateHeader();

    String firstNameCell = table.getCellContents(1, 0);
    parameterized = isNameParameterized(firstNameCell);
    name = getScenarioName();
    getTestContext().addScenario(name, this);
    getScenarioArguments();
  }

  private void getScenarioArguments() {
    if (parameterized) {
      getArgumentsForParameterizedName();
    } else {
      getArgumentsForAlternatingName();
    }
  }

  private void getArgumentsForAlternatingName() {
    for (int inputCol = 2; inputCol < colsInHeader; inputCol += 2) {
      String argName = table.getCellContents(inputCol, 0);

      if (argName.endsWith("?")) {
        String disgracedArgName = Disgracer.disgraceMethodName(argName.substring(
          0, argName.length()));
        outputs.add(disgracedArgName);
      } else {
        String disgracedArgName = Disgracer.disgraceMethodName(argName);
        inputs.add(disgracedArgName);
      }
    }
  }

  private void getArgumentsForParameterizedName() {
    String argumentString = table.getCellContents(2, 0);
    String[] arguments = argumentString.split(",");

    for (String argument : arguments) {
      inputs.add(Disgracer.disgraceMethodName(argument.trim()));
    }
  }

  private String getScenarioName() {
    if (parameterized) {
      String parameterizedName = table.getCellContents(1, 0);

      return unparameterize(parameterizedName);
    } else {
      return getNameFromAlternatingCells();
    }
  }

  public static boolean isNameParameterized(String firstNameCell) {
    Pattern regPat = Pattern.compile(underscorePattern);
    Matcher underscoreMatcher = regPat.matcher(firstNameCell);

    return underscoreMatcher.find();
  }

  public static String unparameterize(String firstNameCell) {
    String name = firstNameCell.replaceAll(underscorePattern, " ").trim();

    return Disgracer.disgraceClassName(name);
  }

  private String getNameFromAlternatingCells() {
    StringBuffer nameBuffer = new StringBuffer();

    for (int nameCol = 1; nameCol < colsInHeader; nameCol += 2)
      nameBuffer.append(table.getCellContents(nameCol, 0)).append(" ");

    return Disgracer.disgraceClassName(nameBuffer.toString().trim());
  }

  private void validateHeader() throws SyntaxError {
    if (colsInHeader <= 1) {
      throw new SyntaxError("Scenario tables must have a name.");
    }
  }

  public String getName() {
    return name;
  }

  public Set<String> getInputs() {
    return new HashSet<String>(inputs);
  }

  public Set<String> getOutputs() {
    return outputs;
  }

  public List<Object> call(Map<String, String> scenarioArguments,
                   SlimTable parentTable, int row) throws SyntaxError {
    String script = getTable().toHtml();
    script = replaceArgsInScriptTable(script, scenarioArguments);
    return insertAndProcessScript(script, parentTable, row);
  }

  public List<Object> call(String[] args, ScriptTable parentTable, int row) throws SyntaxError {
    Map<String, String> scenarioArguments = new HashMap<String, String>();

    for (int i = 0; (i < inputs.size()) && (i < args.length); i++)
      scenarioArguments.put(inputs.get(i), args[i]);

    return call(scenarioArguments, parentTable, row);
  }

  private List<Object> insertAndProcessScript(String script, SlimTable parentTable,
                                      int row) {
    try {
      // TODO: retrieve table scanner from context
      TableScanner ts = new HtmlTableScanner(script);
      ScriptTable t = new ScriptTable(ts.getTable(0), id,
        parentTable.getTestContext());
      parentTable.addChildTable(t, row);
      List<Object> instructions = t.getInstructions();
      parentTable.addExpectation(new ScenarioExpectation(t, row));
      return instructions;
    } catch (Exception e) {
      throw new SlimError(e);
    }
  }

  private String replaceArgsInScriptTable(String script, Map<String, String> scenarioArguments) throws SyntaxError {
    for (Map.Entry<String, String> scenarioArgument : scenarioArguments.entrySet()) {
      String arg = scenarioArgument.getKey();
      if (getInputs().contains(arg)) {
        String argument = scenarioArguments.get(arg);
        script = StringUtil.replaceAll(script, "@" + arg, argument);
        script = StringUtil.replaceAll(script, "@{" + arg + "}", argument);
      } else {
        throw new SyntaxError(String.format(
          "The argument %s is not an input to the scenario.", arg));
      }
    }

    return script;
  }

  public boolean isParameterized() {
    return parameterized;
  }

///// scriptTable matcher logic:
  public String[] matchParameters(String invokingString) {
    String parameterizedName;

    if (parameterized) {
      parameterizedName = table.getCellContents(1, 0);
    } else if (this.inputs.size() > 0) {
      StringBuilder nameBuffer = new StringBuilder();

      for (int nameCol = 1; nameCol < colsInHeader; nameCol += 2)
        nameBuffer.append(table.getCellContents(nameCol, 0))
          .append(" _ ");

      parameterizedName = nameBuffer.toString().trim();
    } else {
      return null;
    }

    return getArgumentsMatchingParameterizedName(parameterizedName,
      invokingString);
  }

  private String[] getArgumentsMatchingParameterizedName(
    String parameterizedName, String invokingString) {
    Matcher matcher = makeParameterizedNameMatcher(parameterizedName,
      invokingString);

    if (matcher.matches()) {
      return extractNamesFromMatcher(matcher);
    } else {
      return null;
    }
  }

  private Matcher makeParameterizedNameMatcher(String parameterizedName,
                                               String invokingString) {
    String patternString = parameterizedName.replaceAll("_", "(.*)");
    Pattern pattern = Pattern.compile(patternString);
    Matcher matcher = pattern.matcher(invokingString);

    return matcher;
  }

  private String[] extractNamesFromMatcher(Matcher matcher) {
    String[] arguments = new String[matcher.groupCount()];

    for (int i = 0; i < arguments.length; i++) {
      arguments[i] = matcher.group(i + 1);
    }

    return arguments;
  }
//// till here

  private class ScenarioExpectation extends RowExpectation {
    private ScriptTable scriptTable;

    private ScenarioExpectation(ScriptTable scriptTable, int row) {
      super("", -1, row); // We don't care about anything but the row.
      this.scriptTable = scriptTable;
    }

    public void evaluateExpectation(Object returnValue) {
      TestSummary counts = scriptTable.getTestSummary();
      SlimTable parent = scriptTable.getParent();
      ExecutionResult testStatus = ExecutionResult.getExecutionResult(counts);
      parent.getTable().setTestStatusOnRow(getRow(), testStatus);
      parent.getTestSummary().add(scriptTable.getTestSummary());
    }

    protected Result createEvaluationMessage(String actual, String expected) {
      return null;
    }
  }
}