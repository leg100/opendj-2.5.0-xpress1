/*
 * CDDL HEADER START
 *
 * The contents of this file are subject to the terms of the
 * Common Development and Distribution License, Version 1.0 only
 * (the "License").  You may not use this file except in compliance
 * with the License.
 *
 * You can obtain a copy of the license at
 * trunk/opends/resource/legal-notices/OpenDS.LICENSE
 * or https://OpenDS.dev.java.net/OpenDS.LICENSE.
 * See the License for the specific language governing permissions
 * and limitations under the License.
 *
 * When distributing Covered Code, include this CDDL HEADER in each
 * file and include the License file at
 * trunk/opends/resource/legal-notices/OpenDS.LICENSE.  If applicable,
 * add the following below this CDDL HEADER, with the fields enclosed
 * by brackets "[]" replaced with your own identifying information:
 *      Portions Copyright [yyyy] [name of copyright owner]
 *
 * CDDL HEADER END
 *
 *
 *      Copyright 2008-2010 Sun Microsystems, Inc.
 *      Portions Copyright 2011-2012 ForgeRock AS
 */
package org.opends.build.tools;

import org.apache.tools.ant.Task;
import org.apache.tools.ant.BuildException;
import org.apache.tools.ant.Project;
import static org.opends.build.tools.Utilities.*;
import org.opends.messages.Category;
import org.opends.messages.Severity;
import org.opends.messages.MessageDescriptor;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Properties;
import java.util.List;
import java.util.ArrayList;
import java.util.UnknownFormatConversionException;
import java.util.Calendar;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.HashSet;
import java.util.Set;
import java.util.EnumSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Generates a Java class containing representations of messages
 * found in a properties file.
 */
public class GenerateMessageFile extends Task {

  private File source;
  private File dest;
  private boolean overwrite;

  static private final String MESSAGES_FILE_STUB =
          "resource/Messages.java.stub";

  /*
   * The registry filename is the result of the concatenation of the
   * location of where the source are generated, the package name and the
   * DESCRIPTORS_REG value.
   */
  static private String REGISTRY_FILE_NAME;

  static private final String DESCRIPTORS_REG = "descriptors.reg";

  /**
   * Used to set a category for all messages in the property file.
   * If set, the category for each message need not be encoded in
   * the message's property file key.
   */
  static private final String GLOBAL_CATEGORY = "global.category";

  /**
   * Used to set a severity for all messages in the property file.
   * If set, the severity for each message need not be encoded in
   * the message's property file key.
   */
  static private final String GLOBAL_SEVERITY = "global.severity";

  /**
   * Used to set a category mask for all messages in the property
   * file.  If set, the category will automatically be assigned
   * USER_DEFINED and the value of <code>GLOBAL_CATEGORY</code>
   * will be ignored.
   */
  static private final String GLOBAL_CATEGORY_MASK = "global.mask";

  /**
   * When true generates messages that have no ordinals.
   */
  static private final String GLOBAL_ORDINAL = "global.ordinal";

  /**
   * When true and if the Java Web Start property is set use the class loader of
   * the jar where the MessageDescriptor is contained to retrieve the
   * ResourceBundle.
   */
  static private final String GLOBAL_USE_MESSAGE_JAR_IF_WEBSTART =
    "global.use.message.jar.if.webstart";

  static private final Set<String> DIRECTIVE_PROPERTIES = new HashSet<String>();
  static {
    DIRECTIVE_PROPERTIES.add(GLOBAL_CATEGORY);
    DIRECTIVE_PROPERTIES.add(GLOBAL_CATEGORY_MASK);
    DIRECTIVE_PROPERTIES.add(GLOBAL_SEVERITY);
    DIRECTIVE_PROPERTIES.add(GLOBAL_ORDINAL);
    DIRECTIVE_PROPERTIES.add(GLOBAL_USE_MESSAGE_JAR_IF_WEBSTART);
  }

  static private final String SPECIFIER_REGEX =
          "%(\\d+\\$)?([-#+ 0,(\\<]*)?(\\d+)?(\\.\\d+)?([tT])?([a-zA-Z%])";

  private final Pattern SPECIFIER_PATTERN = Pattern.compile(SPECIFIER_REGEX);

  /**
   * Message giving formatting rules for string keys.
   */
  static public String KEY_FORM_MSG;

  static {
    KEY_FORM_MSG = new StringBuilder()
            .append(".\n\nOpenDJ message property keys must be of the form\n\n")
            .append("\t\'[CATEGORY]_[SEVERITY]_[DESCRIPTION]_[ORDINAL]\'\n\n")
            .append("where\n\n")
            .append("CATEGORY is one of ")
            .append(EnumSet.allOf(Category.class))
            .append("\n\nSEVERITY is one of ")
            .append(Severity.getPropertyKeyFormSet().toString())
            .append("\n\nDESCRIPTION is a descriptive string composed ")
            .append("of uppercase character, digits and underscores ")
            .append("describing the purpose of the message ")
            .append("\n\nORDINAL is an integer between 0 and 65535 that is ")
            .append("unique to other messages defined in this file.\n\n")
            .append("You can relax the mandate for including the CATEGORY, ")
            .append("SEVERITY, and/or ORDINAL by including one or more ")
            .append("of the following respective property directives in your ")
            .append("properties file:  ")
            .append(GLOBAL_CATEGORY)
            .append(", ")
            .append(GLOBAL_SEVERITY)
            .append(", ")
            .append(GLOBAL_ORDINAL)
            .append("and setting their value appropriately.")
            .toString();
  }

  /*
   * ISO_LANGUAGES contains all official supported languages for i18n
   */
  private static final List<String> ISO_LANGUAGES =
                                        Arrays.asList(Locale.getISOLanguages());
  /*
   * ISO_COUNTRIES contains all official supported countries for i18n
   */
  private static final List<String> ISO_COUNTRIES =
                                        Arrays.asList(Locale.getISOCountries());

  /*
   * A Pattern instance that matches "<label>_<language>_<country>.properties"
   * where <label> can be anything including '_'
   *       <language> a two characters code contained in the ISO_LANGUAGES list
   *       <country> a two characters code contained in the ISO_COUNTRIES list
   */
  private static final Pattern LANGUAGE_COUNTRY_MATCHER =
                       Pattern.compile("(.*)_([a-z]{2})_([A-Z]{2}).properties");
  /*
   * A Pattern instance that matches "<label>_<language>.properties"
   * where <label> and <language> have same definition as above.
   */
  private static final Pattern LANGUAGE_MATCHER =
                       Pattern.compile("(.*)_([a-z]{2}).properties");

  /**
   * Representation of a format specifier (for example %s).
   */
  private class FormatSpecifier {

    private String[] sa;

    /**
     * Creates a new specifier.
     * @param sa specifier components
     */
    FormatSpecifier(String[] sa) {
      this.sa = sa;
    }

    /**
     * Indicates whether or not the specifier uses argument
     * indexes (for example 2$).
     * @return boolean true if this specifier uses indexing
     */
    public boolean specifiesArgumentIndex() {
      return this.sa[0] != null;
    }

    /**
     * Returns a java class associated with a particular formatter
     * based on the conversion type of the specifier.
     * @return Class for representing the type of argument used
     *         as a replacement for this specifier.
     */
    public Class<?> getSimpleConversionClass() {
      Class<?> c = null;
      String sa4 = sa[4] != null ? sa[4].toLowerCase() : null;
      String sa5 = sa[5] != null ? sa[5].toLowerCase() : null;
      if ("t".equals(sa4)) {
        c = Calendar.class;
      } else if (
              "b".equals(sa5)) {
        c = Boolean.class;
      } else if (
              "h".equals(sa5)) {
        c = Integer.class;
      } else if (
              "s".equals(sa5)) {
        c = CharSequence.class;
      } else if (
              "c".equals(sa5)) {
        c = Character.class;
      } else if (
              "d".equals(sa5) ||
              "o".equals(sa5) ||
              "x".equals(sa5) ||
              "e".equals(sa5) ||
              "f".equals(sa5) ||
              "g".equals(sa5) ||
              "a".equals(sa5)) {
        c = Number.class;
      } else if (
              "n".equals(sa5) ||
              "%".equals(sa5)) {
        // ignore literals
      }
      return c;
    }

  }

  /**
   * Represents a message to be written into the messages files.
   */
  private class MessageDescriptorDeclaration {

    private MessagePropertyKey key;
    private String formatString;
    private List<FormatSpecifier> specifiers;
    private List<Class<?>> classTypes;
    private String[] constructorArgs;

    /**
     * Creates a parameterized instance.
     * @param key of the message
     * @param formatString of the message
     */
    public MessageDescriptorDeclaration(MessagePropertyKey key,
                                     String formatString) {
      this.key = key;
      this.formatString = formatString;
      this.specifiers = parse(formatString);
      this.classTypes = new ArrayList<Class<?>>();
      for (FormatSpecifier f : specifiers) {
        Class<?> c = f.getSimpleConversionClass();
        if (c != null) {
          classTypes.add(c);
        }
      }
    }

    /**
     * Gets the name of the Java class that will be used to represent
     * this message's type.
     * @return String representing the Java class name
     */
    public String getDescriptorClassDeclaration() {
      StringBuilder sb = new StringBuilder();
      if (useGenericMessageTypeClass()) {
        sb.append(getShortClassName(MessageDescriptor.class));
        sb.append(".");
        sb.append(MessageDescriptor.DESCRIPTOR_CLASS_BASE_NAME);
        sb.append("N");
      } else {
        sb.append(getShortClassName(MessageDescriptor.class));
        sb.append(".");
        sb.append(MessageDescriptor.DESCRIPTOR_CLASS_BASE_NAME);
        sb.append(classTypes.size());
        sb.append(getClassTypeVariables());
      }
      return sb.toString();
    }

    /**
     * Gets a string representing the message type class' variable
     * information (for example '<String,Integer>') that is based on
     * the type of arguments specified  by the specifiers in this message.
     * @return String representing the message type class parameters
     */
    public String getClassTypeVariables() {
      StringBuilder sb = new StringBuilder();
      if (classTypes.size() > 0) {
        sb.append("<");
        for (int i = 0; i < classTypes.size(); i++) {
          Class<?> c = classTypes.get(i);
          if (c != null) {
            sb.append(getShortClassName(c));
            if (i < classTypes.size() - 1) {
              sb.append(",");
            }
          }
        }
        sb.append(">");
      }
      return sb.toString();
    }

    /**
     * Gets the javadoc comments that will appear above the messages declaration
     * in the messages file.
     * @return String comment
     */
    public String getComment() {
      StringBuilder sb = new StringBuilder();
      sb.append(indent(1)).append("/**").append(EOL);

      // Unwrapped so that you can search through the descriptor
      // file for a message and not have to worry about line breaks
      String ws = formatString; // wrapText(formatString, 70);

      String[] sa = ws.split(EOL);
      for (String s : sa) {
        sb.append(indent(1)).append(" * ").append(s).append(EOL);
      }
      sb.append(indent(1)).append(" */").append(EOL);
      return sb.toString();
    }

    /**
     * Sets the arguments that will be supplied in the declaration
     * of the message.
     * @param s array of string arguments that will be passed
     *        in the constructor
     */
    public void setConstructorArguments(String... s) {
      this.constructorArgs = s;
    }

    /**
     * {@inheritDoc}
     */
    public String toString() {
      StringBuilder sb = new StringBuilder();
      sb.append(getComment());
      sb.append(indent(1));
      sb.append("public static final ");
      sb.append(getDescriptorClassDeclaration());
      sb.append(" ");
      sb.append(key.getMessageDescriptorName());
      sb.append(" =");
      sb.append(EOL);
      sb.append(indent(5));
      sb.append("new ");
      sb.append(getDescriptorClassDeclaration());
      sb.append("(");
      if (constructorArgs != null) {
        for (int i = 0; i < constructorArgs.length; i++) {
          sb.append(constructorArgs[i]);
          if (i < constructorArgs.length - 1) {
            sb.append(",");
          }
        }
        sb.append(", ");
      }
      sb.append("getClassLoader()");
      sb.append(");");
      return sb.toString();
    }

    /**
     * Indicates whether the generic message type class should
     * be used.  In general this is when a format specifier is
     * more complicated than we support or when the number of
     * arguments exceeeds the number of specific message type
     * classes (MessageType0, MessageType1 ...) that are defined.
     * @return boolean indicating
     */
    private boolean useGenericMessageTypeClass() {
      if (specifiers.size() > MessageDescriptor.DESCRIPTOR_MAX_ARG_HANDLER) {
        return true;
      } else if (specifiers != null) {
        for (FormatSpecifier s : specifiers) {
          if (s.specifiesArgumentIndex()) {
            return true;
          }
        }
      }
      return false;
    }

    /**
     * Look for format specifiers in the format string.
     * @param s format string
     * @return list of format specifiers
     */
    private List<FormatSpecifier> parse(String s) {
      List<FormatSpecifier> sl = new ArrayList<FormatSpecifier>();
      Matcher m = SPECIFIER_PATTERN.matcher(s);
      int i = 0;
      while (i < s.length()) {
        if (m.find(i)) {
          // Anything between the start of the string and the beginning
          // of the format specifier is either fixed text or contains
          // an invalid format string.
          if (m.start() != i) {
            // Make sure we didn't miss any invalid format specifiers
            checkText(s.substring(i, m.start()));
            // Assume previous characters were fixed text
            //al.add(new FixedString(s.substring(i, m.start())));
          }

          // Expect 6 groups in regular expression
          String[] sa = new String[6];
          for (int j = 0; j < m.groupCount(); j++) {
            sa[j] = m.group(j + 1);
          }
          sl.add(new FormatSpecifier(sa));
          i = m.end();
        } else {
          // No more valid format specifiers.  Check for possible invalid
          // format specifiers.
          checkText(s.substring(i));
          // The rest of the string is fixed text
          //al.add(new FixedString(s.substring(i)));
          break;
        }
      }
      return sl;
    }

    private void checkText(String s) {
      int idx;
      // If there are any '%' in the given string, we got a bad format
      // specifier.
      if ((idx = s.indexOf('%')) != -1) {
        char c = (idx > s.length() - 2 ? '%' : s.charAt(idx + 1));
        throw new UnknownFormatConversionException(String.valueOf(c));
      }
    }

  }

  /**
   * Sets the source of the messages.
   * @param source File representing the properties
   *        file containing messages
   */
  public void setSourceProps(File source) {
    this.source = source;
  }

  /**
   * Sets the file that will be generated containing
   * declarations of messages from <code>source</code>.
   * @param dest File destination
   */
  public void setDestJava(File dest) {
    this.dest = dest;

    try {
      File descriptorsRegFile =
        new File(dest.getParentFile(), DESCRIPTORS_REG);

      if (REGISTRY_FILE_NAME != null) {
        // if REGISTRY_FILE_NAME is already set, ensure that we computed the
        // same one
        File prevDescriptorsRegFile = new File(REGISTRY_FILE_NAME);
        if (!prevDescriptorsRegFile.equals(descriptorsRegFile)) {
          throw new BuildException("Error processing " + dest
            + ": all messages must be located in the same package thus "
            + "name of the source file should be "
            + new File(prevDescriptorsRegFile.getParent(), dest.getName()));
        }
      } else {
        REGISTRY_FILE_NAME = descriptorsRegFile.getCanonicalPath();
      }
    } catch (Exception e) {
      throw (new BuildException(e));
    }
  }

  /**
   * Indicates when true that an existing destination
   * file will be overwritten.
   * @param o boolean where true means overwrite
   */
  public void setOverwrite(boolean o) {
    this.overwrite = o;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void execute() throws BuildException {

    if ( this.dest == null ) {
      // this is an example-plugin call:
      // - check the source file is not a localization
      // - guess the destination filename from source filename
      String sourcefilename = source.getAbsolutePath();
      int filenameIndex = sourcefilename.lastIndexOf(File.separator)+1;
      String pathname = sourcefilename.substring(0, filenameIndex);
      String filename = sourcefilename.substring(filenameIndex);

      /*
       * Make sure only <label>.properties are generated thus avoiding to
       * generate messages for localized properties files.
       */
      Matcher matcher = LANGUAGE_COUNTRY_MATCHER.matcher(filename);
      if ( matcher.find() ) {
        if ( ISO_LANGUAGES.contains(matcher.group(2))
          && ISO_COUNTRIES.contains(matcher.group(3)) ) {
          // do not generate message for <label>_<language>_<country>.properties
          return;
        }
      }

      matcher = LANGUAGE_MATCHER.matcher(filename);
      if ( matcher.find() ) {
        if ( ISO_LANGUAGES.contains(matcher.group(2)) ) {
          // do not generate message for <label>_<language>.properties
          return;
        }
      }

      // filename without ".properties"
      filename = filename.substring(0, filename.length()-11);
      // change to src-generated directory keeping package name
      pathname = pathname.replace(getProject().getProperty("msg.dir"),
                                  getProject().getProperty("msg.javagen.dir"));


      // append characters from filename to pathname starting with an uppercase
      // letter, ignoring '_' and uppering all characters prefixed with "_"
      StringBuilder sb = new StringBuilder(pathname);
      boolean upperCaseNextChar = true;
      for(char c : filename.toCharArray()) {
        if ( c == '_' || c == '-' ) {
          upperCaseNextChar = true;
          continue;
        }
        if ( upperCaseNextChar ) {
          sb.append(Character.toUpperCase(c));
          upperCaseNextChar = false;
        } else {
          sb.append(c);
        }
      }
      sb.append("Messages.java");

      setDestJava(new File(sb.toString()));
    }

    BufferedReader stubReader = null;
    PrintWriter destWriter = null;
    try {

      // Decide whether to generate messages based on modification
      // times and print status messages.
      if (!source.exists()) {
        throw new BuildException("file " + source.getName() +
                " does not exist");
      }
      if (dest.exists()) {
        if (this.overwrite || source.lastModified() > dest.lastModified()) {
          dest.delete();
          log("Regenerating " + dest.getName() + " from " + source.getName());
        } else {
          log(dest.getName() + " is up to date");
          return;
        }
      } else {
        File javaGenDir = dest.getParentFile();
        if (!javaGenDir.exists()) {
          javaGenDir.mkdirs();
        }
        log("Generating " + dest.getName() + " from " + source.getName());
      }

      stubReader = new BufferedReader(new InputStreamReader(getStubFile(),
                                                            "UTF-8"));
      destWriter = new PrintWriter(dest, "UTF-8");
      String stubLine;
      Properties properties = new Properties();
      properties.load(new FileInputStream(source));
      while (null != (stubLine = stubReader.readLine())) {
        if (stubLine.contains("${MESSAGES}")) {
          Integer globalOrdinal = null;
          String go = properties.getProperty(GLOBAL_ORDINAL);
          if (go != null) {
            globalOrdinal = new Integer(go);
          }

          // Determine the value of the global category/mask if set
          Integer  globalMask = null;
          Category globalCategory = null;
          String gms = properties.getProperty(GLOBAL_CATEGORY_MASK);
          if (gms != null) {
            globalMask = Integer.parseInt(gms);
            globalCategory = Category.USER_DEFINED;
          } else {
            String gcs = properties.getProperty(GLOBAL_CATEGORY);
            if (gcs != null) {
              globalCategory = Category.valueOf(gcs);
            }
          }

          // Determine the value of the global severity
          Severity globalSeverity = null;
          String gss = properties.getProperty(GLOBAL_SEVERITY);
          if (gss != null) {
            globalSeverity = Severity.parseString(gss);
          }

          Map<MessagePropertyKey,String> keyMap =
                  new TreeMap<MessagePropertyKey,String>();

          for (Object propO : properties.keySet()) {
            String propKey = propO.toString();
            try {
              if (!DIRECTIVE_PROPERTIES.contains(propKey)) {
                MessagePropertyKey key =
                        MessagePropertyKey.parseString(
                                propKey,
                                globalCategory == null,
                                globalSeverity == null,
                                globalOrdinal == null);
                String formatString = properties.getProperty(propKey);
                keyMap.put(key, formatString);
              }
            } catch (IllegalArgumentException iae) {
              throw new BuildException(
                      "ERROR: invalid property key " + propKey +
                      ": " + iae.getMessage() +
                      KEY_FORM_MSG);
            }
          }

          int usesOfGenericDescriptor = 0;

          Category firstCategory = null;
          Set<Integer> usedOrdinals = new HashSet<Integer>();
          for (MessagePropertyKey key : keyMap.keySet()) {
            String formatString = keyMap.get(key);
            MessageDescriptorDeclaration message =
                    new MessageDescriptorDeclaration(key, formatString);

            Category c = (globalCategory != null ?
                    globalCategory : key.getCategory());

            // Check that this category is the same as all the
            // others in this file.  Maybe this should be an error?
            if (firstCategory != null) {
              if (!firstCategory.equals(c)) {
                log("WARNING: multiple categories defined in " + source);
              }
            } else {
              firstCategory = c;
            }

            Severity s = (globalSeverity != null ?
                    globalSeverity : key.getSeverity());

            if (c == null) {
              throw new BuildException(
                      "No category could be assigned to message " +
                              key + ".  The category " +
                              "must either be encoded in the property key or " +
                              "or must be set by including the property " +
                              GLOBAL_CATEGORY + " in the properties file" +
                              KEY_FORM_MSG);
            }

            if (s == null) {
              throw new BuildException(
                      "No severity could be assigned to message " +
                              key + ".  The severity " +
                              "must either be encoded in the property key or " +
                              "or must be set by including the property " +
                              GLOBAL_SEVERITY + " in the properties file" +
                              KEY_FORM_MSG);
            }

            if (globalOrdinal == null) {
              Integer ordinal = key.getOrdinal();
              if (usedOrdinals.contains(ordinal)) {
                throw new BuildException(
                        "The ordinal value \'" + ordinal + "\' in key " +
                                key + " has been previously defined in " +
                                source + KEY_FORM_MSG);
              } else {
                usedOrdinals.add(ordinal);
              }
            }

            message.setConstructorArguments(
                    "BASE",
                    quote(key.toString()),
                    globalMask != null ? globalMask.toString() : c.name(),
                    s.name(),
                    globalOrdinal != null ?
                            globalOrdinal.toString() :
                            key.getOrdinal().toString()
            );
            destWriter.println(message.toString());
            destWriter.println();

            // Keep track of when we use the generic descriptor
            // so that we can report it later
            if (message.useGenericMessageTypeClass()) {
              usesOfGenericDescriptor++;
            }
          }

          log("  Message Generated:" + keyMap.size(), Project.MSG_VERBOSE);
          log("  MessageDescriptor.ArgN:" + usesOfGenericDescriptor,
                  Project.MSG_VERBOSE);

        } else {
          stubLine = stubLine.replace("${PACKAGE}", getPackage());
          stubLine = stubLine.replace("${CLASS_NAME}",
                  dest.getName().substring(0, dest.getName().length() -
                          ".java".length()));
          stubLine = stubLine.replace("${BASE}", getBase());

          String useMessageJarIfWebstart =
            properties.getProperty(GLOBAL_USE_MESSAGE_JAR_IF_WEBSTART);
          if ((useMessageJarIfWebstart != null) &&
              ("true".equalsIgnoreCase(useMessageJarIfWebstart) ||
              "on".equalsIgnoreCase(useMessageJarIfWebstart) ||
              "true".equalsIgnoreCase(useMessageJarIfWebstart)))
          {
            useMessageJarIfWebstart = "true";
          }
          else
          {
            useMessageJarIfWebstart = "false";
          }
          stubLine = stubLine.replace("${USE_MESSAGE_JAR_IF_WEBSTART}",
              useMessageJarIfWebstart);
          destWriter.println(stubLine);
        }
      }

      registerMessageDescriptor(getMessageDescriptorFullClassName());

      stubReader.close();
      destWriter.close();

    } catch (Exception e) {
      // Don't leave a malformed file laying around. Delete
      // it so it will be forced to be regenerated.
      if (dest.exists()) {
        dest.deleteOnExit();
      }
      e.printStackTrace();
      throw new BuildException("Error processing " + source +
              ":  " + e.getMessage());
    } finally {
      if (stubReader != null) {
        try {
          stubReader.close();
        } catch (Exception e){
          // ignore
        }
      }
      if (destWriter != null) {
        try {
          destWriter.close();
        } catch (Exception e){
          // ignore
        }
      }
    }
  }

  private String getMessageDescriptorFullClassName() {
    return getPackage() + "." + getMessageDescriptorClassName();
  }

  private String getMessageDescriptorClassName() {
    return dest.getName().substring(
            0, dest.getName().length() - ".java".length());
  }

  private String getBase() {
    String srcPath = unixifyPath(source.getAbsolutePath());
    String base = srcPath.substring(srcPath.lastIndexOf("/") + 1,
                                    srcPath.length() - ".properties".length());
    return base;
  }

  private String getPackage() {
    String destPath = unixifyPath(dest.getAbsolutePath());
    String msgJavaGenDir = unixifyPath(
                                   getProject().getProperty("msg.javagen.dir"));
    String c = destPath.substring(msgJavaGenDir.length()+1);
    c = c.replace('/', '.');
    c = c.substring(0, c.lastIndexOf(".")); // strip .java
    c = c.substring(0, c.lastIndexOf(".")); // strip class name
    return c;
  }

  static private String indent(int indent) {
    char[] blankArray = new char[2 * indent];
    Arrays.fill(blankArray, ' ');
    return new String(blankArray);
  }

  static private String quote(String s) {
    return new StringBuilder()
            .append("\"")
            .append(s)
            .append("\"")
            .toString();
  }

  static private String getShortClassName(Class<?> c) {
    String name;
    String fqName = c.getName();
    int i = fqName.lastIndexOf('.');
    if (i > 0) {
      name = fqName.substring(i + 1);
    } else {
      name = fqName;
    }
    return name;
  }

  /**
   * Writes a record in the messages registry for the specifed
   * class name.
   * @param descClassName name of the message descriptor class
   * @return true if the class was acutally added to the registry;
   *         false indicates that the class was already present.
   * @throws IOException if there is a problem with the file I/O
   */
  private boolean registerMessageDescriptor(String descClassName)
          throws IOException
  {
    boolean classAdded = false;
    File registry = getRegistryFile();
    if (!isDescriptorRegistered(descClassName)) {
      FileOutputStream file = new FileOutputStream(registry,true);
      DataOutputStream out   = new DataOutputStream(file);
      out.writeBytes(descClassName);
      out.writeBytes("\n");
      out.flush();
      out.close();
    }
    return classAdded;
  }

  private boolean isDescriptorRegistered(String descClassName)
          throws IOException
  {
    boolean isRegistered = false;
    BufferedReader reader = new BufferedReader(
            new FileReader(getRegistryFile()));
    String line;
    while(null != (line = reader.readLine())) {
      if (line.trim().equals(descClassName.trim())) {
        isRegistered = true;
        break;
      }
    }
    return isRegistered;
  }

  private File getRegistryFile() throws IOException {
    File registry = new File(REGISTRY_FILE_NAME);
    if (!registry.exists()) {
      File parent = registry.getParentFile();
      if (!parent.exists()) {
        parent.mkdirs();
      }
      registry.createNewFile();
    }
    return registry;
  }

  private File getProjectBase() {
    return getProject().getBaseDir();
  }

  private String unixifyPath(String path) {
    return path.replace("\\", "/");
  }

  /*
   * Returns the stub file ("resource/Messages.java.stub") from the appropriate
   * location: ant or jar file.
   */
  private InputStream getStubFile() {
    InputStream result = null;

    File stub = new File(getProjectBase(), MESSAGES_FILE_STUB);
    if ( stub.exists() ) {
      // this is the OpenDS's ant project calling
      // Stub is located at OPENDS_ROOT/resource/Messages.java.stub
      try {
        result = new FileInputStream(stub);
      } catch (FileNotFoundException e) {
        // should neven happen
        throw new BuildException("Unable to load template " +
              MESSAGES_FILE_STUB + ":  " + e.getMessage());
      }
    } else {
      // this is the example plugin's ant project calling
      // Stub is located at build-tools.jar:resource/Messages.java.stub
      result = getClass().getResourceAsStream(MESSAGES_FILE_STUB);
    }

    return result;
  }

  /**
   * For testing.
   * @param args from command line
   */
  public static void main(String[] args) {
    File source = new File("src/messages/messages/tools.properties");
    File dest = new File("/tmp/org/opends/XXX.java");
    GenerateMessageFile gmf = new GenerateMessageFile();
    gmf.setOverwrite(true);
    gmf.setDestJava(dest);
    gmf.setSourceProps(source);
    gmf.execute();
  }

}
