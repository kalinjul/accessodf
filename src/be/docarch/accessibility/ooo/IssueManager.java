package be.docarch.accessibility.ooo;

import java.util.Date;
import java.util.logging.Logger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.HashMap;
import java.util.Collection;
import java.util.Collections;
import java.io.File;

import java.text.ParseException;
import java.io.IOException;

import com.sun.star.lang.EventObject;
import com.sun.star.container.XEnumeration;
import com.sun.star.rdf.Statement;
import com.sun.star.rdf.XURI;
import com.sun.star.rdf.XResource;
import com.sun.star.rdf.XRepository;
import com.sun.star.rdf.XNamedGraph;

import com.sun.star.container.NoSuchElementException;
import com.sun.star.rdf.RepositoryException;
import com.sun.star.lang.WrappedTargetException;
import com.sun.star.lang.IllegalArgumentException;
import com.sun.star.beans.UnknownPropertyException;
import com.sun.star.beans.PropertyVetoException;

import be.docarch.accessibility.Check;
import be.docarch.accessibility.Checker;
import be.docarch.accessibility.ExternalChecker;
import be.docarch.accessibility.Constants;
import be.docarch.accessibility.Report;

/**
 *
 * @author Bert Frees
 */
public class IssueManager {

    private static final Logger logger = Logger.getLogger(Constants.LOGGER_NAME);
    private static final String TMP_NAME = Constants.TMP_PREFIX;
    private static final File TMP_DIR = Constants.getTmpDirectory();
    
    public static enum Status { ALERT, ERROR, REPAIRED, IGNORED };

    private Document document = null;
    private Settings settings = null;
    private InternalChecker internalChecker = null;
    private List<ExternalChecker> externalCheckers = null;
    private Repairer repairer = null;

    private List<Issue> allIssues = null;
    private FilterSorter filterSorter = null;
    private Issue selectedIssue = null;
    private Check selectedCheck = null;
    private Map<Check,List<Issue>> check2IssuesMap = null;
    //private Map<Check,Checker> check2checkerMap = null;
    //private Map<Check,Repairer> check2repairerMap = null;

    public IssueManager(Document document,
                        InternalChecker internalChecker,
                        List<ExternalChecker> externalCheckers)
                 throws IllegalArgumentException,
                        NoSuchElementException,
                        RepositoryException,
                        PropertyVetoException,
                        WrappedTargetException,
                        ParseException,
                        com.sun.star.uno.Exception {
    
        logger.entering("IssueManager", "<init>");

        this.document = document;
        this.internalChecker = internalChecker;
        this.externalCheckers = externalCheckers;
        settings = new Settings(document.xContext);
        repairer = new InternalRepairer(document);

        //check2checkerMap = new HashMap<Check,Checker>();
        //check2repairerMap = new HashMap<Check,Repairer>();

        Map<String,Check> checks = new TreeMap<String,Check>();
        for (Check check : internalChecker.getChecks()) {
            checks.put(check.getIdentifier(), check);
            //check2checkerMap.put(check, internalChecker);
        }
        for (Checker checker : externalCheckers) {
            for (Check check : checker.getChecks()) {
                checks.put(check.getIdentifier(), check);
                //check2checkerMap.put(check, checker);
            }
        }
        Issue.init(document, checks);

        check2IssuesMap = new HashMap<Check,List<Issue>>();

        filterSorter = new FilterSorter();
        filterSorter.setOrderPriority(FilterSorter.NAME, true);
        filterSorter.setOrderPriority(FilterSorter.CHECKID, true);
        filterSorter.setOrderPriority(FilterSorter.CATEGORY, true);
        allIssues = new ArrayList<Issue>();
        
        // Load accessibility issues from metadata
        loadMetadata();

        logger.exiting("IssueManager", "<init>");
        
    }

    public void refresh() throws IllegalArgumentException,
                                 RepositoryException,
                                 UnknownPropertyException,
                                 NoSuchElementException,
                                 WrappedTargetException,
                                 PropertyVetoException,
                                 ParseException,
                                 IOException,
                                 com.sun.star.uno.Exception {

        // Extract accessibility issues from document and store in metadata
        internalChecker.check();

        // Get external accessibility reports and store in metadata
        settings.loadData();
        File odtFile = null;

        for (ExternalChecker checker : externalCheckers) {
            if (!settings.brailleChecks() && checker.getIdentifier().startsWith("be.docarch.odt2braille")) {
                break;
            }
            if (odtFile == null) {
                odtFile = File.createTempFile(TMP_NAME, ".odt", TMP_DIR);
                odtFile.deleteOnExit();
                document.ensureMetadataReferences();
                document.storeToFile(odtFile);
            }
            checker.setOdtFile(odtFile);
            if (checker.check()) {
                Report report = checker.getAccessibilityReport();
                if (report != null) {
                    document.importAccessibilityData(report.getFile(), report.getName());
                }
            }
        }

        // Load accessibility issues from metadata
        loadMetadata();
    }

    public void clear() throws IllegalArgumentException,
                               RepositoryException,
                               PropertyVetoException,
                               NoSuchElementException {

        XNamedGraph[] accessibilityDataGraphs = getAccessibilityDataGraphs();

        for (XNamedGraph accessibilityDataGraph : accessibilityDataGraphs) {
            document.xDMA.removeMetadataFile(accessibilityDataGraph.getName());
        }
        if (allIssues.size() > 0) {
            document.setModified();
            allIssues.clear();
        }
    }

    private XNamedGraph[] getAccessibilityDataGraphs() throws IllegalArgumentException,
                                                              RepositoryException {
    
        XRepository xRepository = document.xDMA.getRDFRepository();
        XURI[] graphNames = document.xDMA.getMetadataGraphsWithType(URIs.CHECKER);
        XNamedGraph[] accessibilityDataGraphs = new XNamedGraph[graphNames.length];
        for (int i=0; i<accessibilityDataGraphs.length; i++) {
            accessibilityDataGraphs[i] = xRepository.getGraph(graphNames[i]);
        }

        return accessibilityDataGraphs;
    }

    private void loadMetadata() throws NoSuchElementException,
                                       RepositoryException,
                                       WrappedTargetException,
                                       IllegalArgumentException,
                                       PropertyVetoException,
                                       ParseException {

        logger.entering("IssueManager", "loadMetadata");

        XNamedGraph[] accessibilityDataGraphs = getAccessibilityDataGraphs();

        allIssues.clear();

        Map<String,Date> mostRecentCheckDates = new HashMap<String,Date>();
        Issue issue, sameIssue;
        Date d1, d2;
        String checker;

        for (XNamedGraph accessibilityDataGraph : accessibilityDataGraphs) {
            XEnumeration assertions = accessibilityDataGraph.getStatements(null, URIs.RDF_TYPE, URIs.EARL_ASSERTION);
            XResource assertion = null;
            while (assertions.hasMoreElements()) {
                assertion = ((Statement)assertions.nextElement()).Subject;
                issue = new Issue(assertion, accessibilityDataGraph);
                if (!issue.valid()) {
                    break;
                }
                if (!issue.getElement().exists()) {
                    logger.info("Element does not exist, removing issue");
                    issue.remove();
                    break;
                }
                if (allIssues.contains(issue)) {
                    sameIssue = allIssues.remove(allIssues.indexOf(issue));
                    if (issue.getCheckDate().before(sameIssue.getCheckDate())) {
                        if (issue.ignored()) {
                            sameIssue.ignored(true);
                        }
                        issue.remove();
                        issue = sameIssue;
                    } else {
                        if (sameIssue.ignored()) {
                            issue.ignored(true);
                        }
                        sameIssue.remove();
                    }
                }
                allIssues.add(issue);
                checker = issue.getChecker();
                d1 = issue.getCheckDate();
                d2 = mostRecentCheckDates.get(checker);
                if (d2==null || d2.before(d1)) {
                    mostRecentCheckDates.put(checker, d1);
                }
            }
        }

        for (Issue i : allIssues) {
            checker = i.getChecker();
            d1 = i.getCheckDate();
            d2 = mostRecentCheckDates.get(checker);
            if (d1.before(d2)) {
                i.repaired(true);
            }
        }

        logger.exiting("IssueManager", "loadMetadata");

    }

    public void select(Object o) {

        selectedCheck = null;
        selectedIssue = null;

        if (o != null) {
            if (o instanceof Check) {
                selectedCheck = (Check)o;
            } else if (o instanceof Issue) {
                selectedIssue = (Issue)o;
                selectedCheck = selectedIssue.getCheck();
            }
        }
    }

    public boolean repair(Issue issue)
                   throws IllegalArgumentException,
                          RepositoryException,
                          PropertyVetoException,
                          NoSuchElementException {

        if (issue == null) { return false; }
        boolean succes = repairer.repair(issue);
        issue.repaired(succes);
        return succes;
    }

    public void arrangeIssues() {

        Collections.sort(allIssues, filterSorter);

        check2IssuesMap.clear();

        Check prevCheck = null;
        ArrayList<Issue> issueList = null;
        for (Issue issue : allIssues) {
            Check check = issue.getCheck();
            if (!check.equals(prevCheck)) {
                issueList = new ArrayList<Issue>();
                check2IssuesMap.put(check, issueList);
            }
            issueList.add(issue);
            prevCheck = check;
        }
    }

    public Collection<Check> getChecks() {
        return check2IssuesMap.keySet();
    }

    public List<Issue> getIssuesByCheck(Check check) {
        return check2IssuesMap.get(check);
    }

    public Status getCheckStatus(Check check) {

        boolean alert = (check.getStatus() == Check.Status.ALERT);
        boolean allRepaired = true;
        boolean allIgnored = true;
        for (Issue issue : check2IssuesMap.get(check)) {
            if (!issue.ignored()) {
                allIgnored = false;
                if (!issue.repaired()) {
                    allRepaired = false;
                    break;
                }
            }
        }
        return (allIgnored?  Status.IGNORED:
                allRepaired? Status.REPAIRED:
                alert?       Status.ALERT:
                             Status.ERROR);
    }

    public Status getIssueStatus(Issue issue) {

        boolean alert = (issue.getCheck().getStatus() == Check.Status.ALERT);

        return (issue.ignored()?  Status.IGNORED:
                issue.repaired()? Status.REPAIRED:
                alert?            Status.ALERT:
                                  Status.ERROR);
    }

    public Issue selectedIssue() {
        return selectedIssue;
    }

    public Check selectedCheck() {
        return selectedCheck;
    }

    public void disposing(EventObject event) {}

}
