package it.unibo.tw;
import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.AbstractMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.json.simple.JSONObject;
import org.json.simple.JSONValue;

public class Main {
	
	private static JDBCGenerator jdbcGenerator;
	private static DAOGenerator daoGenerator;
	private static HibernateGenerator hibGenerator;
	private static final String pkgFolder = "src/it/unibo/tw";
	private static final String pkg = "it.unibo.tw";
	private static Map<String, Map<String, String>> fieldsFromName = new HashMap<String, Map<String, String>>();
	private static List<Entry<String, String>> names = new ArrayList<Entry<String, String>>(); // singular, plural
	//private static List<Entry<String, Entry<String, String>>> relations = new ArrayList<Entry<String, Entry<String, String>>>(); // N:M -> <a,b>, 1:N -> <a,c>
	private static Map <String, Entry<String, Entry<String, String>>> relations = new HashMap<String, Entry<String, Entry<String, String>>>(); // modelXXX associate with cardinality X:X  A and B
	private static Map<String, String> constraintsByName = new HashMap<String, String>();
	private static Map<String, String> singlePlural = new HashMap<String, String>();
	private static String tableName, tableNamePlural, constraints;
	private static Map<String, String> fields = new HashMap<String, String>();
	private static String line, username, password;
	
	private static void parseConstraint() {
		String[] lastLine = line.split("\\)")[1].trim().split("-");
		if(lastLine.length < 2) {
			constraints = "";
			return;
		}
		String constraint = lastLine[1].replace('<', '(').replace('>', ')');
		String contraintType = constraint.substring(0, constraint.indexOf("("));
		String[] keys = constraint.replace(contraintType, "").replace(")", "").replace("(", "").trim().split(",");

		for(int i = 0; i< keys.length; i++) {
			String key = keys[i].trim();
			if(fields.get(key).contains("REFERENCES")) {
				keys[i] = "id" + key;
			}
		}
		constraints = contraintType + " ( " + String.join(", ", keys) + " )";
	}
	
	private static void getTableName() {
		tableName = Utils.UcFirst(line.split("\\(")[0].trim());
	}
	
	private static void getTableNamePlural() {
		String[] lastLine = line.split("\\)")[1].trim().split("-");
		tableNamePlural = Utils.UcFirst(lastLine[0].trim());
	}
	
	private static void saveField() {
		String[] field = line.trim().split(" ");
		// Name, Type [ FK REFERENCES <Tablename> ]
		String typeAndRef = String.join(" ", Arrays.copyOfRange(field, 1, field.length));
		fields.put( Utils.UcFirst(field[0].trim()), Utils.UcFirst(typeAndRef.trim()));
	}
	
	private static void saveAssociations() {
		// save associations
		fieldsFromName.put(tableName.toLowerCase(), new HashMap<String, String>(fields));
		names.add( new AbstractMap.SimpleEntry<String, String>(tableName, tableNamePlural));
		constraintsByName.put(tableName.toLowerCase(), new String(constraints));
		singlePlural.put(tableName, tableNamePlural);
	}
	
	private static void generateEntity(boolean skipHibernate) throws Exception {
		// JDBC
		jdbcGenerator = new JDBCGenerator(pkgFolder, pkg, tableName, fields, tableNamePlural, constraints, singlePlural, username, password);
		jdbcGenerator.writeBean();
		jdbcGenerator.writeManager();
		// DAO
		daoGenerator = new DAOGenerator(pkgFolder, pkg, tableName, fields, tableNamePlural, constraints, singlePlural, username, password);
		daoGenerator.writeDTO();
		daoGenerator.writeDAO();
		// Hibernate
		if(!skipHibernate) {
			hibGenerator = new HibernateGenerator(pkgFolder, pkg, tableName, fields, tableNamePlural, constraints, singlePlural, username, password);
			hibGenerator.writeBeans();
			hibGenerator.writeModelCfg();
		}
	}

	/* See tables.txt to see a valid syntax example */
	public static void main(String[] args) throws Exception {
		BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream("src/tables.txt")));
		BufferedReader r2 = new BufferedReader(new InputStreamReader(new FileInputStream("src/config.json")));
		String json = "";
		while((line = r2.readLine()) != null) {
			json += line;
		}
		r2.close();

		JSONObject data = (JSONObject)JSONValue.parse(json);
		username = (String) data.get("username");
		password = (String) data.get("password");
		System.out.println("[!] Credentials read");
		
		//parse ./tables.txt
		while((line = reader.readLine()) != null) {
			// skip white lines and comments --
			if(line.equals("") || line.startsWith("--")) {
				continue;
			}
			
			if(line.contains(":")) { // relations - last lines
				Pattern p = Pattern.compile("\\s*([a-z0-9]:[a-z0-9])\\s*<([a-z0-9_]+)\\s*,\\s*([a-z0-9_]+)\\s*>",Pattern.CASE_INSENSITIVE);
				Matcher m = p.matcher(line);
				if(m.find()) {
					String relationType = m.group(1).toLowerCase();
					String leftEntity = m.group(2);
					String rightEntity = m.group(3);
					// contains table definition
					if(line.contains("{")) {
						while(!(line = reader.readLine()).contains("}")) {
							if(line.contains("(")) {
								getTableName();
							} else if(line.contains(")")) {
								getTableNamePlural();
								relations.put(tableNamePlural, new AbstractMap.SimpleEntry<String, Entry<String, String>>(relationType, new AbstractMap.SimpleEntry<String, String>(leftEntity, rightEntity)));
								parseConstraint();
								generateEntity(true);
								saveAssociations();
								// clear
								fields.clear();
							} else {
								saveField();
							}
						}
					}
				} else {
					System.err.println("Syntax error");
					System.exit(1);
				}
				
			} else if(line.contains("(")) { // begin table definition
				getTableName();
			} else if(line.contains(")")) { // end table definition
				getTableNamePlural();
				// add Long ID to field set (always present)
				fields.put("Id", "Long");
				// set constraints
				parseConstraint();
				// Generate
				generateEntity(false);
				// save associations
				saveAssociations();
				// clear
				fields.clear();
			} else { //field
				saveField();
			}
		}
		reader.close();
		// DAO //
		List<Entry<String, String>> daoNames = new ArrayList<Entry<String, String>>(names.size());
		for(Entry<String, String> name : names) {
			daoNames.add(new AbstractMap.SimpleEntry<String,String>(name.getKey() + "DAO", ""));
		}
		// Generate Factories
		daoGenerator.writeFactories(daoNames);
		// Generate Main
		daoGenerator.writeMainTest(daoNames, fieldsFromName);
		
		// JDBC //
		// Generate DataSource
		jdbcGenerator.writeDataSource();
		// Generate Main
		jdbcGenerator.writeMainTest(names, fieldsFromName);
		
		// Hibernate //
		// Generate Main
		hibGenerator.writeMainTest(names, fieldsFromName, constraintsByName, relations);
		// Generate hibernate.cfg.xml
		hibGenerator.writeCfgXML();
		
		// End
		System.out.println("Please press F5 in the eclipse Project.\n"
				+ "Go into models folders (model,dao,hibernate) and: Source -> generate hashCode() and equals()");
	}

}
