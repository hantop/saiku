package org.saiku.repository;

import com.marklogic.xcc.*;
import com.marklogic.xcc.exceptions.RequestException;
import com.marklogic.xcc.exceptions.XccConfigException;
import org.apache.commons.lang3.text.StrSubstitutor;
import org.saiku.database.dto.MondrianSchema;
import org.saiku.datasources.connection.RepositoryFile;
import org.saiku.service.user.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jcr.RepositoryException;
import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Marshaller;
import javax.xml.bind.Unmarshaller;
import java.io.*;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.*;

/**
 * MarkLogic Repository Manager
 */
public class MarkLogicRepositoryManager implements IRepositoryManager {
  private static final Logger log = LoggerFactory.getLogger(MarkLogicRepositoryManager.class);

  private static final String[] PARAMETER_DELIMITER = new String[]{"%(", ")"};
  private static final String HOMES_DIRECTORY = "/homes/";
  private static final String DATASOURCES_DIRECTORY = "/datasources/";
  private static final String SCHEMAS_DIRECTORY = "/schemas/";

  private String host = "localhost";
  private int port = 8070;
  private String username = "admin";
  private String password = "bruunoo";
  private String database = "saiku";

  private String connectionUrl;
  private ContentSource contentSource;

  private UserService userService;
  private static MarkLogicRepositoryManager instance;

  private MarkLogicRepositoryManager(String host, int port, String username, String password, String database) {
    this.host = host;
    this.port = port;
    this.username = username;
    this.password = password;
    this.database = database;
  }

  public static synchronized MarkLogicRepositoryManager getMarkLogicRepositoryManager(String host, int port, String username, String password, String database) {
    if (instance == null) {
      instance = new MarkLogicRepositoryManager(host, port, username, password, database);
    }

    return instance;
  }

  @Override
  public void init() {
    Map<String, String> values = ParamsMap.init()
        .put("host", host)
        .put("port", Integer.toString(port))
        .put("username", username)
        .put("password", password)
        .put("database", database).build();

    StrSubstitutor sub = createStrSubstitutor(values);

    connectionUrl = sub.replace("xcc://%(username):%(password)@%(host):%(port)/%(database)");

    connect();
  }

  @Override
  public boolean start(UserService userService) throws RepositoryException {
    this.userService = userService;

    if (!folderExists(HOMES_DIRECTORY)) {
      createFolder(HOMES_DIRECTORY);
    }

    if (!folderExists(DATASOURCES_DIRECTORY)) {
      createFolder(DATASOURCES_DIRECTORY);
    }

    return true;
  }

  @Override
  public void createUser(String u) throws RepositoryException {
    createFolder(HOMES_DIRECTORY + u + "/");
  }

  @Override
  public Object getHomeFolders() throws RepositoryException {
    return getFilesFromFolder(HOMES_DIRECTORY, true);
  }

  @Override
  public Object getHomeFolder(String directory) throws RepositoryException {
    return getFilesFromFolder(HOMES_DIRECTORY + directory, false);
  }

  @Override
  public Object getFolder(String user, String directory) throws RepositoryException {
    return getFilesFromFolder(HOMES_DIRECTORY + user + "/" + directory + "/", false);
  }

  @Override
  public void shutdown() {
  }

  @Override
  public boolean createFolder(String username, String folder) throws RepositoryException {
    createFolder(HOMES_DIRECTORY + username + "/" + folder + "/");
    return true;
  }

  @Override
  public boolean deleteFolder(String folder) throws RepositoryException {
    executeUpdate("xdmp:directory-delete('%(folder)')", ParamsMap.init().put("folder", folder).build());
    return true;
  }

  @Override
  public void deleteRepository() throws RepositoryException {

  }

  @Override
  public boolean moveFolder(String user, String folder, String source, String target) throws RepositoryException {
    /* TODO - MarkLogic doest not support moving directories or documents, instead you should create another directory
       and document with the same content, with another name, and delete the old directory/document. */
    return false;
  }

  @Override
  public Object saveFile(Object file, String path, String user, String type, List<String> roles) throws RepositoryException {
    if (file == null) { // Create a folder
      createFolder(user, path);
    } else { // Write a file to an existing folder
      path = HOMES_DIRECTORY + user + "/" + path;

      Session session = createUpdateSession();

      ContentCreateOptions options = new ContentCreateOptions();
      options.setFormat(DocumentFormat.TEXT);

      Content content = ContentFactory.newContent(path, (String)file, options);

      try {
        session.insertContent(content);
        session.commit();
      } catch (RequestException e) {
        log.error("Error while trying to save the file: " + path, e);
        throw new RepositoryException(e);
      }

      return new File(path);
    }

    return null;
  }

  @Override
  public void removeFile(String path, String user, List<String> roles) throws RepositoryException {
    Map<String, String> params = ParamsMap.init().put("doc_uri", HOMES_DIRECTORY + user + "/" + path).build();
    executeUpdate("xdmp:document-delete('%(doc_uri)')", params);
  }

  @Override
  public void moveFile(String source, String target, String user, List<String> roles) throws RepositoryException {
    /* TODO - MarkLogic doest not support moving directories or documents, instead you should create another directory
       and document with the same content, with another name, and delete the old directory/document. */
  }

  @Override
  public Object saveInternalFile(Object file, String path, String type) throws RepositoryException {
    if (file == null) { // Create a folder
      createFolder(path);
    } else { // Write a file to an existing folder
      Map<String, String> params = ParamsMap.init()
          .put("path", path)
          .put("text", escapeForJava((String)file, true)).build();

      executeUpdate("xdmp:document-insert('%(path)', text {%(text)})", params);

      return new File(path);
    }

    return null;
  }

  @Override
  public Object saveBinaryInternalFile(InputStream file, String path, String type) throws RepositoryException {
    Session session = createUpdateSession();

    ContentCreateOptions options = new ContentCreateOptions();
    options.setFormat(DocumentFormat.BINARY);

    try {
      Content content = ContentFactory.newContent(path, file, options);
      session.insertContent(content);

      return new File(path);
    } catch (IOException ex) {
      log.error("Error while trying to save the file", ex);
      throw new RepositoryException(ex);
    } catch (RequestException ex) {
      log.error("Error while trying to save the file", ex);
      throw new RepositoryException(ex);
    }
  }

  @Override
  public String getFile(String s, String username, List<String> roles) throws RepositoryException {
    Session session = contentSource.newSession();

    RequestOptions options = new RequestOptions();
    options.setCacheResult(false); // stream by default

    AdhocQuery request = session.newAdhocQuery("doc('" + s + "')", options);

    try {
      ResultSequence rs = session.submitRequest(request);
      ResultItem item = rs.next();

      if (item == null) {
        throw new RepositoryException("No document found with URI '" + s + "'");
      }

      StringWriter writer = new StringWriter();
      item.writeTo(writer);

      return writer.toString();
    } catch (RequestException e) {
      log.error("Error whilte trying to fetch the file " + s, e);
      throw new RepositoryException(e);
    } catch (IOException e) {
      log.error("Error whilte trying to fetch the file " + s, e);
      throw new RepositoryException(e);
    }
  }

  @Override
  public String getInternalFile(String s) throws RepositoryException {
    return getFile(s, null, null);
  }

  @Override
  public InputStream getBinaryInternalFile(String s) throws RepositoryException {
    Session session = contentSource.newSession();

    RequestOptions options = new RequestOptions();
    options.setCacheResult(false); // stream by default

    AdhocQuery request = session.newAdhocQuery("doc('" + s + "')", options);

    try {
      ResultSequence rs = session.submitRequest(request);
      ResultItem item = rs.next();

      if (item == null) {
        throw new RepositoryException("No document found with URI '" + s + "'");
      }

      return item.asInputStream();
    } catch (RequestException e) {
      log.error("Error whilte trying to fetch the binary file " + s, e);
      throw new RepositoryException(e);
    }
  }

  @Override
  public void removeInternalFile(String s) throws RepositoryException {
    Map<String, String> params = ParamsMap.init().put("doc_uri", s).build();
    executeUpdate("xdmp:document-delete('%(doc_uri)')", params);
  }

  @Override
  public List<MondrianSchema> getAllSchema() throws RepositoryException {
    List<MondrianSchema> schemas = new ArrayList<>();

    for (File file : getFilesFromFolder(SCHEMAS_DIRECTORY, false)) {
      MondrianSchema ms = new MondrianSchema();

      ms.setName(file.getName());
      ms.setPath(file.getPath());

      schemas.add(ms);
    }

    return schemas;
  }

  @Override
  public List<DataSource> getAllDataSources() throws RepositoryException {
    List<DataSource> dataSources = new ArrayList<>();

    for (File file : getFilesFromFolder(DATASOURCES_DIRECTORY, false)) {
      InputStream fileContent = getBinaryInternalFile(file.getPath());

      JAXBContext jaxbContext = null;
      Unmarshaller jaxbMarshaller = null;

      try {
        jaxbContext = JAXBContext.newInstance(DataSource.class);
      } catch (JAXBException e) {
        log.error("Could instantiate the JAXBContent", e);
      }

      try {
        jaxbMarshaller = jaxbContext != null ? jaxbContext.createUnmarshaller() : null;
      } catch (JAXBException e) {
        log.error("Could not create the XML unmarshaller", e);
      }

      DataSource d = null;

      try {
        d = (DataSource) (jaxbMarshaller != null ? jaxbMarshaller.unmarshal(fileContent) : null);
      } catch (JAXBException e) {
        log.error("Could not unmarshall the XML file", e);
      }

      if (d != null) {
        d.setPath(file.getPath());
        dataSources.add(d);
      }
    }

    return dataSources;
  }

  @Override
  public void saveDataSource(DataSource ds, String path, String user) throws RepositoryException {
    Session session = createUpdateSession();

    ContentCreateOptions options = new ContentCreateOptions();
    options.setFormat(DocumentFormat.BINARY);

    ByteArrayOutputStream baos = new ByteArrayOutputStream();

    try {
      JAXBContext jaxbContext = JAXBContext.newInstance(DataSource.class);
      Marshaller jaxbMarshaller = jaxbContext.createMarshaller();

      // output pretty printed
      jaxbMarshaller.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, true);

      // marshall the datasource to a byte array
      jaxbMarshaller.marshal(ds, baos);

      // create an input stream from the byte array
      ByteArrayInputStream bis = new ByteArrayInputStream(baos.toByteArray());

      // create a document from the input stream
      Content content = ContentFactory.newContent(path, bis, options);
      session.insertContent(content);
      session.commit();
    } catch (JAXBException e) {
      log.error("Could not marshall the datasource", e);
      throw new RepositoryException(e);
    } catch (RequestException e) {
      log.error("Could not marshall the datasource", e);
      throw new RepositoryException(e);
    } catch (IOException e) {
      log.error("Could not marshall the datasource", e);
      throw new RepositoryException(e);
    }
  }

  @Override
  public byte[] exportRepository() throws RepositoryException, IOException {
    return null;
  }

  @Override
  public void restoreRepository(byte[] xml) throws RepositoryException, IOException {

  }

  @Override
  public RepositoryFile getFile(String fileUrl) {
    File f = new File(fileUrl);
    return new RepositoryFile(f.getName(), null, null, f.getPath());
  }

  @Override
  public List<IRepositoryObject> getAllFiles(List<String> type, String username, List<String> roles) {
    return null;
  }

  @Override
  public List<IRepositoryObject> getAllFiles(List<String> type, String username, List<String> roles, String path) throws RepositoryException {
    List<IRepositoryObject> files = new ArrayList<>();

    Acl2 acl = new Acl2(new File(path));

    // First, fetch all the directories, recursively
    List<String> dirUris = new ArrayList<>();
    getRecursiveDirectories(path, dirUris);

    for (String dirUri : dirUris) {
      List<IRepositoryObject> dirFiles = new ArrayList<>();

      if (dirUri.equals(path)) {
        dirFiles = files;
      }

      // Then, fetch all the files from the directory
      for (File f : getFilesFromFolder(path, false)) {
        for (String fileType : type) {
          if (f.getName().toLowerCase().endsWith(fileType.toLowerCase())) {
            List<AclMethod> acls = acl.getMethods(f, username, roles);
            dirFiles.add(new RepositoryFileObject(f.getName(), "#" + f.getPath(), fileType, f.getPath(), acls));
          }
        }
      }

      // Add subdirs and its files
      if (!dirUri.equals(path)) {
        sortFiles(dirFiles);
        List<AclMethod> acls = acl.getMethods(new File(dirUri), username, roles);
        files.add(new RepositoryFolderObject(dirUri, "#" + dirUri, dirUri, acls, dirFiles));
      }
    }

    sortFiles(files);
    return files;
  }

  private void getRecursiveDirectories(String root, List<String> foundSoFar) throws RepositoryException {
    if (foundSoFar.contains(root)) {
      return;
    }

    foundSoFar.add(root);

    for (File f : getFilesFromFolder(root, true)) {
      getRecursiveDirectories(f.getPath(), foundSoFar);
    }
  }

  private void sortFiles(List<IRepositoryObject> files) {
    // Sort the files so directories come first
    Collections.sort(files, new Comparator<IRepositoryObject>() {

      public int compare(IRepositoryObject o1, IRepositoryObject o2) {
        if (o1.getType().equals(IRepositoryObject.Type.FOLDER) && o2.getType().equals(IRepositoryObject.Type.FILE))
          return -1;
        if (o1.getType().equals(IRepositoryObject.Type.FILE) && o2.getType().equals(IRepositoryObject.Type.FOLDER))
          return 1;
        return o1.getName().toLowerCase().compareTo(o2.getName().toLowerCase());
      }

    });
  }

  @Override
  public void deleteFile(String datasourcePath) {
    try {
      removeInternalFile(datasourcePath);
    } catch (RepositoryException e) {
      log.error("Error while trying to delete the file: " + datasourcePath, e);
    }
  }

  @Override
  public AclEntry getACL(String object, String username, List<String> roles) {
    return null;
  }

  @Override
  public void setACL(String object, String acl, String username, List<String> roles) throws RepositoryException {

  }

  @Override
  public List<MondrianSchema> getInternalFilesOfFileType(String type) throws RepositoryException {
    return this.getAllSchema();
  }

  @Override
  public void createFileMixin(String type) throws RepositoryException {

  }

  @Override
  public Object getRepositoryObject() {
    return null;
  }

  private void connect() {
    try {
      URI uri = new URI(connectionUrl);
      contentSource = ContentSourceFactory.newContentSource (uri);
    } catch (URISyntaxException e) {
      log.error("Incorrect URI syntax: " + connectionUrl, e);
      throw new RuntimeException(e);
    } catch (XccConfigException e) {
      log.error("Wrong XCC configuration", e);
      throw new RuntimeException(e);
    }
  }

  private File[] getFilesFromFolder(String path, boolean directories) throws RepositoryException {
    if (!path.endsWith("/")) {
      path = path + "/";
    }

    String regexEnd = directories ? ".+/$" : ".+[^/]$";

    Session session = contentSource.newSession();
    AdhocQuery request = session.newAdhocQuery("cts:uris()[matches(., '^" + path + regexEnd + "')]");

    try {
      ResultSequence rs = session.submitRequest(request);
      File[] folders = new File[rs.size()];

      int i = 0;
      while(rs.hasNext()) {
        folders[i++] = new File(rs.next().getDocumentURI());
      }

      return folders;
    } catch (RequestException e) {
      log.error("Error while trying to fetch the home folders", e);
      throw new RepositoryException(e);
    } finally {
      session.close();
    }
  }

  private void createFolder(String path) throws RepositoryException {
    executeUpdate("xdmp:directory-create('%(path)')", ParamsMap.init().put("path", path).build());
  }

  private boolean folderExists(String directory) throws RepositoryException {
    Session session = contentSource.newSession();
    AdhocQuery request = session.newAdhocQuery("xdmp:exists(xdmp:directory-properties('" + directory + "','1'))");

    try {
      ResultSequence rs = session.submitRequest(request);
      return Boolean.parseBoolean(rs.next().asString());
    } catch (RequestException ex) {
      log.error("Error while trying to check the folder existence", ex);
      throw new RepositoryException(ex);
    }
  }

  private Session createUpdateSession() {
    Session session = contentSource.newSession();
    session.setTransactionMode(Session.TransactionMode.UPDATE);
    return session;
  }

  private void executeUpdate(String update, Map<String, String> parameters) throws RepositoryException {
    executeUpdate(update, parameters, null);
  }

  private void executeUpdate(String update, Map<String, String> parameters, String errorMsg) throws RepositoryException {
    Session session = createUpdateSession();

    AdhocQuery request = null;

    if (parameters != null) {
      StrSubstitutor sub = createStrSubstitutor(parameters);
      request = session.newAdhocQuery(sub.replace(update));
    } else {
      request = session.newAdhocQuery(update);
    }

    try {
      session.submitRequest(request);
      session.commit();
    } catch (RequestException e) {
      log.error(errorMsg != null ? errorMsg : "Error on update", e);
      throw new RepositoryException(e);
    } finally {
      session.close();
    }
  }

  private String escapeForJava( String value, boolean quote) {
    StringBuilder builder = new StringBuilder();

    if (quote) {
      builder.append("\"");
    }

    for (char c : value.toCharArray()) {
      if(c == '\'')
        builder.append( "\\'" );
      else if (c == '\"')
        builder.append( "\\\"" );
      else if(c == '\r')
        builder.append( "\\r" );
      else if(c == '\n')
        builder.append( "\\n" );
      else if(c == '\t')
        builder.append( "\\t" );
      else if(c < 32 || c >= 127)
        builder.append(String.format( "\\u%04x", (int)c));
      else
        builder.append(c);
    }

    if (quote) {
      builder.append("\"");
    }

    return builder.toString();
  }

  /**
   * Factory Method
   * @param values
   * @return
   */
  private static StrSubstitutor createStrSubstitutor(Map<String, String> values) {
    return new StrSubstitutor(values, PARAMETER_DELIMITER[0], PARAMETER_DELIMITER[1]);
  }

  /**
   *
   */
  private static class ParamsMap {
    private Map<String, String> params;

    private ParamsMap() {
      params = new HashMap<>();
    }

    public static ParamsMap init() {
      return new ParamsMap();
    }

    public ParamsMap put(String key, String value) {
      params.put(key, value);
      return this;
    }

    public Map<String, String> build() {
      return params;
    }
  }
}
