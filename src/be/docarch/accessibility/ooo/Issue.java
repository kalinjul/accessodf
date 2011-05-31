package be.docarch.accessibility.ooo;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Map;
import java.util.logging.Logger;
import java.util.logging.Level;

import com.sun.star.container.XEnumeration;
import com.sun.star.rdf.Statement;
import com.sun.star.rdf.XURI;
import com.sun.star.rdf.URI;
import com.sun.star.rdf.BlankNode;
import com.sun.star.rdf.XResource;
import com.sun.star.rdf.XNamedGraph;
import com.sun.star.rdf.Literal;

import java.text.ParseException;

import com.sun.star.container.NoSuchElementException;
import com.sun.star.rdf.RepositoryException;
import com.sun.star.lang.WrappedTargetException;
import com.sun.star.lang.IllegalArgumentException;
import com.sun.star.beans.PropertyVetoException;

import be.docarch.accessibility.Check;
import be.docarch.accessibility.Constants;

/**
 *
 * @author Bert Frees
 */
public class Issue {

    private static final Logger logger = Logger.getLogger(Constants.LOGGER_NAME);
    
    private static Document document = null;
    private static Map<String,Check> checks = null;

    private XNamedGraph graph = null;
    private XResource assertion = null;
    private XResource testresult = null;
    private XURI testcase = null;

    private Element element = null;
    private Check check = null;
    private String checker = null;
    private boolean valid = false;
    private boolean ignored = false;
    private boolean repaired = false;
    protected int index = 0;
    protected int position = 0;
    protected Date checkDate = null;

    static void init(Document document,
                     Map<String,Check> checks)
              throws IllegalArgumentException {

        Issue.document = document;
        Issue.checks = checks;
        Element.initialise(document);

    }

    public Issue(XResource assertion,
                 XNamedGraph graph)
          throws IllegalArgumentException,
                 RepositoryException,
                 NoSuchElementException,
                 WrappedTargetException,
                 Exception {

        logger.entering("Issue", "<init>");

        try {

            if (!graph.getStatements(assertion, URIs.RDF_TYPE, URIs.EARL_ASSERTION).hasMoreElements()) {
                return;
            }
            this.graph = graph;
            this.assertion = assertion;
            XEnumeration assertors = graph.getStatements(assertion, URIs.EARL_ASSERTEDBY, null);
            XEnumeration testsubjects = graph.getStatements(assertion, URIs.EARL_SUBJECT, null);
            XEnumeration testcases = graph.getStatements(assertion, URIs.EARL_TEST, null);
            XEnumeration testresults = graph.getStatements(assertion, URIs.EARL_RESULT, null);
            if (!assertors.hasMoreElements() ||
                !testsubjects.hasMoreElements() ||
                !testcases.hasMoreElements() ||
                !testresults.hasMoreElements()) {
                return;
            }
            testcase = URI.create(document.xContext, ((Statement)testcases.nextElement()).Object.getStringValue());
            testresult = BlankNode.create(document.xContext, ((Statement)testresults.nextElement()).Object.getStringValue());
            XResource testsubject = BlankNode.create(document.xContext, ((Statement)testsubjects.nextElement()).Object.getStringValue());
            XResource assertor = BlankNode.create(document.xContext, ((Statement)assertors.nextElement()).Object.getStringValue());
            if (!graph.getStatements(testresult, URIs.EARL_OUTCOME, null).hasMoreElements() ||
                !graph.getStatements(testresult, URIs.RDF_TYPE, URIs.EARL_TESTRESULT).hasMoreElements() ||
                !graph.getStatements(testsubject, URIs.RDF_TYPE, URIs.EARL_TESTSUBJECT).hasMoreElements() ||
                !graph.getStatements(testcase, URIs.RDF_TYPE, URIs.EARL_TESTCASE).hasMoreElements() ||
                !graph.getStatements(assertor, URIs.RDF_TYPE, URIs.FOAF_GROUP).hasMoreElements()) {
               return;
            }
            if (graph.getStatements(testresult, URIs.EARL_OUTCOME, URIs.EARL_PASSED).hasMoreElements()) {
                repaired = true;
            }
            
            XEnumeration types = graph.getStatements(testsubject, URIs.RDF_TYPE, null);            
            if (!types.hasMoreElements()) {
                return;
            }
            
            String t = ((Statement)types.nextElement()).Object.getStringValue();
            if (t.equals(Constants.CHECKER_DOCUMENT)) {
                element = null;
            } else if (t.equals(Constants.CHECKER_PARAGRAPH)) {
                element = new Paragraph(testsubject);
            } else if (t.equals(Constants.CHECKER_SPAN)) {
                element = new Span(testsubject);
            } else if (t.equals(Constants.CHECKER_TABLE)) {
                element = new Table(testsubject);
            } else if (t.equals(Constants.CHECKER_OBJECT)) {
                element = new DrawObject(testsubject);
            } else {
                return;
            }

            check = checks.get(testcase.getLocalName());
            if (check == null) {
                return;
            }
            XEnumeration mainAssertors = graph.getStatements(assertor, URIs.EARL_MAINASSERTOR, null);
            if (!mainAssertors.hasMoreElements()) {
                return;
            }
            XURI mainAssertor = URI.create(document.xContext, ((Statement)mainAssertors.nextElement()).Object.getStringValue());
            if (!graph.getStatements(mainAssertor, URIs.RDF_TYPE, URIs.EARL_SOFTWARE).hasMoreElements()) {
                return;
            }
            checker = mainAssertor.getStringValue();
            XEnumeration timestamps = graph.getStatements(testresult, URIs.CHECKER_LASTCHECKED, null);
            if (!timestamps.hasMoreElements()) {
                return;
            }
            checkDate = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ").parse(
                    ((Statement)timestamps.nextElement()).Object.getStringValue());
            valid = true;
            XEnumeration ignore = graph.getStatements(testresult, URIs.CHECKER_IGNORE, null);
            if (ignore.hasMoreElements()) {
                if (((Statement)ignore.nextElement()).Object.getStringValue().equals("true")) {
                    ignored = true;
                }
            }
        
        } catch (ParseException ex) {
            logger.log(Level.SEVERE, null, ex);
        } finally {
            if (!valid) {
                throw new Exception("Invalid assertion");
            }
            logger.exiting("Issue", "<init>");
        }
    }

    public Element getElement() {
        return element;
    }

    public boolean ignored() {
        return ignored;
    }

    public boolean repaired() {
        return repaired;
    }

    public void ignored(boolean ignored)
                 throws IllegalArgumentException,
                        PropertyVetoException,
                        RepositoryException,
                        NoSuchElementException{

        this.ignored = ignored;
        graph.removeStatements(testresult, URIs.CHECKER_IGNORE, null);
        if (ignored) {
            graph.addStatement(testresult, URIs.CHECKER_IGNORE, Literal.create(document.xContext, "true"));
        }
        document.setModified();
    }

    public void repaired(boolean repaired)
                  throws IllegalArgumentException,
                         RepositoryException,
                         PropertyVetoException,
                         NoSuchElementException {

        this.repaired = repaired;
        graph.removeStatements(testresult, URIs.EARL_OUTCOME, null);
        if (repaired) {
            graph.addStatement(testresult, URIs.EARL_OUTCOME, URIs.EARL_PASSED);
        } else {
            graph.addStatement(testresult, URIs.EARL_OUTCOME, URIs.EARL_FAILED);
        }
        document.setModified();
    }

    public void remove() throws NoSuchElementException,
                                RepositoryException,
                                PropertyVetoException {
    
        graph.removeStatements(assertion, null, null);
        document.setModified();
    }

    public Date getCheckDate() {
        return checkDate;
    }

    public Check getCheck() {
        return check;
    }

    public String getChecker() {
        return checker;
    }

    public String getName() {

        if (element == null) {
            return "";
        } else {
            return element.toString();
        }
    }

    public Check.Category getCategory() {
        return check.getCategory();
    }

    @Override
    public int hashCode() {

        final int PRIME = 31;
        int hash = 1;
        hash = hash * PRIME + check.hashCode();
        if (element != null) {
            hash = hash * PRIME + element.hashCode();
        }
        return hash;
   }

    @Override
    public boolean equals(Object obj) {

        if (this == obj)
            return true;
        if (obj == null)
            return false;
        if (getClass() != obj.getClass())
            return false;
        final Issue that = (Issue)obj;
        if (!this.check.equals(that.check)) {
            return false;
        }
        if (this.element == null) {
            return (that.element == null);
        } else {
            return this.element.equals(that.element);
        }
    }
}
