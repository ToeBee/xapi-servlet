// This software is released into the Public Domain.  See copying.txt for details.
package com.yellowbkpk.geo.xapi.db;

import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.logging.Logger;

import org.openstreetmap.osmosis.core.OsmosisConstants;
import org.openstreetmap.osmosis.core.container.v0_6.BoundContainer;
import org.openstreetmap.osmosis.core.container.v0_6.BoundContainerIterator;
import org.openstreetmap.osmosis.core.container.v0_6.DatasetContext;
import org.openstreetmap.osmosis.core.container.v0_6.EntityContainer;
import org.openstreetmap.osmosis.core.container.v0_6.EntityManager;
import org.openstreetmap.osmosis.core.container.v0_6.NodeContainer;
import org.openstreetmap.osmosis.core.container.v0_6.NodeContainerIterator;
import org.openstreetmap.osmosis.core.container.v0_6.RelationContainer;
import org.openstreetmap.osmosis.core.container.v0_6.RelationContainerIterator;
import org.openstreetmap.osmosis.core.container.v0_6.WayContainer;
import org.openstreetmap.osmosis.core.container.v0_6.WayContainerIterator;
import org.openstreetmap.osmosis.core.database.DatabaseLoginCredentials;
import org.openstreetmap.osmosis.core.database.DatabasePreferences;
import org.openstreetmap.osmosis.core.domain.v0_6.Bound;
import org.openstreetmap.osmosis.core.domain.v0_6.Node;
import org.openstreetmap.osmosis.core.domain.v0_6.Relation;
import org.openstreetmap.osmosis.core.domain.v0_6.Way;
import org.openstreetmap.osmosis.core.lifecycle.ReleasableIterator;
import org.openstreetmap.osmosis.core.store.MultipleSourceIterator;
import org.openstreetmap.osmosis.core.store.ReleasableAdaptorForIterator;
import org.openstreetmap.osmosis.core.store.UpcastIterator;
import org.openstreetmap.osmosis.pgsnapshot.common.DatabaseContext;
import org.openstreetmap.osmosis.pgsnapshot.common.SchemaVersionValidator;
import org.openstreetmap.osmosis.pgsnapshot.v0_6.PostgreSqlVersionConstants;
import org.openstreetmap.osmosis.pgsnapshot.v0_6.impl.ActionDao;
import org.openstreetmap.osmosis.pgsnapshot.v0_6.impl.DatabaseCapabilityChecker;
import org.openstreetmap.osmosis.pgsnapshot.v0_6.impl.NodeDao;
import org.openstreetmap.osmosis.pgsnapshot.v0_6.impl.PostgreSqlEntityManager;
import org.openstreetmap.osmosis.pgsnapshot.v0_6.impl.RelationDao;
import org.openstreetmap.osmosis.pgsnapshot.v0_6.impl.UserDao;
import org.openstreetmap.osmosis.pgsnapshot.v0_6.impl.WayDao;
import org.postgis.PGgeometry;
import org.postgis.Point;
import org.postgis.Polygon;
import org.springframework.jdbc.core.simple.SimpleJdbcTemplate;

/**
 * Provides read-only access to a PostgreSQL dataset store. Each thread
 * accessing the store must create its own reader. It is important that all
 * iterators obtained from this reader are released before releasing the reader
 * itself.
 * 
 * @author Brett Henderson
 */
public class PostgreSqlDatasetContext implements DatasetContext {

    private static final Logger LOG = Logger.getLogger(PostgreSqlDatasetContext.class.getName());

    private DatabaseLoginCredentials loginCredentials;
    private DatabasePreferences preferences;
    private DatabaseCapabilityChecker capabilityChecker;
    private boolean initialized;
    private DatabaseContext dbCtx;
    private SimpleJdbcTemplate jdbcTemplate;
    private UserDao userDao;
    private NodeDao nodeDao;
    private WayDao wayDao;
    private RelationDao relationDao;
    private PostgreSqlEntityManager<Node> nodeManager;
    private PostgreSqlEntityManager<Way> wayManager;
    private PostgreSqlEntityManager<Relation> relationManager;

    /**
     * Creates a new instance.
     * 
     * @param loginCredentials
     *            Contains all information required to connect to the database.
     * @param preferences
     *            Contains preferences configuring database behaviour.
     */
    public PostgreSqlDatasetContext(DatabaseLoginCredentials loginCredentials, DatabasePreferences preferences) {
        this.loginCredentials = loginCredentials;
        this.preferences = preferences;

        initialized = false;
    }

    /**
     * Initialises the database connection and associated data access objects.
     */
    private void initialize() {
        if (dbCtx == null) {
            ActionDao actionDao;

            dbCtx = new DatabaseContext(loginCredentials);
            jdbcTemplate = dbCtx.getSimpleJdbcTemplate();

            dbCtx.beginTransaction();

            new SchemaVersionValidator(jdbcTemplate, preferences)
                    .validateVersion(PostgreSqlVersionConstants.SCHEMA_VERSION);

            capabilityChecker = new DatabaseCapabilityChecker(dbCtx);

            actionDao = new ActionDao(dbCtx);
            userDao = new UserDao(dbCtx, actionDao);
            nodeDao = new NodeDao(dbCtx, actionDao);
            wayDao = new WayDao(dbCtx, actionDao);
            relationDao = new RelationDao(dbCtx, actionDao);

            nodeManager = new PostgreSqlEntityManager<Node>(nodeDao, userDao);
            wayManager = new PostgreSqlEntityManager<Way>(wayDao, userDao);
            relationManager = new PostgreSqlEntityManager<Relation>(relationDao, userDao);
        }

        initialized = true;
    }

    /**
     * {@inheritDoc}
     */
    @Deprecated
    public Node getNode(long id) {
        return getNodeManager().getEntity(id);
    }

    /**
     * {@inheritDoc}
     */
    @Deprecated
    public Way getWay(long id) {
        return getWayManager().getEntity(id);
    }

    /**
     * {@inheritDoc}
     */
    @Deprecated
    public Relation getRelation(long id) {
        return getRelationManager().getEntity(id);
    }

    /**
     * {@inheritDoc}
     */
    public EntityManager<Node> getNodeManager() {
        if (!initialized) {
            initialize();
        }

        return nodeManager;
    }

    /**
     * {@inheritDoc}
     */
    public EntityManager<Way> getWayManager() {
        if (!initialized) {
            initialize();
        }

        return wayManager;
    }

    /**
     * {@inheritDoc}
     */
    public EntityManager<Relation> getRelationManager() {
        if (!initialized) {
            initialize();
        }

        return relationManager;
    }

    /**
     * {@inheritDoc}
     */
    public ReleasableIterator<EntityContainer> iterate() {
        List<Bound> bounds;
        List<ReleasableIterator<EntityContainer>> sources;

        if (!initialized) {
            initialize();
        }

        // Build the bounds list.
        bounds = new ArrayList<Bound>();
        bounds.add(new Bound("Osmosis " + OsmosisConstants.VERSION));

        sources = new ArrayList<ReleasableIterator<EntityContainer>>();

        sources.add(new UpcastIterator<EntityContainer, BoundContainer>(new BoundContainerIterator(
                new ReleasableAdaptorForIterator<Bound>(bounds.iterator()))));
        sources.add(new UpcastIterator<EntityContainer, NodeContainer>(new NodeContainerIterator(nodeDao.iterate())));
        sources.add(new UpcastIterator<EntityContainer, WayContainer>(new WayContainerIterator(wayDao.iterate())));
        sources.add(new UpcastIterator<EntityContainer, RelationContainer>(new RelationContainerIterator(relationDao
                .iterate())));

        return new MultipleSourceIterator<EntityContainer>(sources);
    }

    /**
     * {@inheritDoc}
     */
    public ReleasableIterator<EntityContainer> iterateBoundingBox(double left, double right, double top, double bottom,
            boolean completeWays) {
        List<Bound> bounds;
        List<LastUpdateTimestamp> timestamps;
        Point[] bboxPoints;
        Polygon bboxPolygon;
        int rowCount;
        List<ReleasableIterator<EntityContainer>> resultSets = new ArrayList<ReleasableIterator<EntityContainer>>();

        if (!initialized) {
            initialize();
        }

        // Build the bounds list.
        bounds = new ArrayList<Bound>();
        bounds.add(new Bound(right, left, top, bottom, "Osmosis " + OsmosisConstants.VERSION));
        
        timestamps = new ArrayList<LastUpdateTimestamp>();
        timestamps.add(new LastUpdateTimestamp(fetchLastUpdate()));

        // PostgreSQL sometimes incorrectly chooses to perform full table scans,
        // these options
        // prevent this. Note that this is not recommended practice according to
        // documentation
        // but fixing this would require modifying the table statistics
        // gathering
        // configuration to produce better plans.
        jdbcTemplate.update("SET enable_seqscan = false");
        jdbcTemplate.update("SET enable_mergejoin = false");
        jdbcTemplate.update("SET enable_hashjoin = false");

        // Build a polygon representing the bounding box.
        // Sample box for query testing may be:
        // GeomFromText('POLYGON((144.93912192855174 -37.82981987499741,
        // 144.93912192855174 -37.79310006709244, 144.98188026000003
        // -37.79310006709244, 144.98188026000003 -37.82981987499741,
        // 144.93912192855174 -37.82981987499741))', -1)
        bboxPoints = new Point[5];
        bboxPoints[0] = new Point(left, bottom);
        bboxPoints[1] = new Point(left, top);
        bboxPoints[2] = new Point(right, top);
        bboxPoints[3] = new Point(right, bottom);
        bboxPoints[4] = new Point(left, bottom);
        bboxPolygon = PolygonBuilder.createPolygon(bboxPoints);

        // Select all nodes inside the box into the node temp table.
        LOG.finer("Selecting all nodes inside bounding box.");
        rowCount = jdbcTemplate.update("CREATE TEMPORARY TABLE bbox_nodes ON COMMIT DROP AS"
                + " SELECT * FROM nodes WHERE (geom && ?)", new PGgeometry(bboxPolygon));

        LOG.finer("Adding a primary key to the temporary nodes table.");
        jdbcTemplate.update("ALTER TABLE ONLY bbox_nodes ADD CONSTRAINT pk_bbox_nodes PRIMARY KEY (id)");

        LOG.finer("Updating query analyzer statistics on the temporary nodes table.");
        jdbcTemplate.update("ANALYZE bbox_nodes");

        // Select all ways inside the bounding box into the way temp table.
        if (capabilityChecker.isWayLinestringSupported()) {
            LOG.finer("Selecting all ways inside bounding box using way linestring geometry.");
            // We have full way geometry available so select ways
            // overlapping the requested bounding box.
            rowCount = jdbcTemplate.update("CREATE TEMPORARY TABLE bbox_ways ON COMMIT DROP AS"
                    + " SELECT * FROM ways WHERE (linestring && ?)", new PGgeometry(bboxPolygon));

        } else if (capabilityChecker.isWayBboxSupported()) {
            LOG.finer("Selecting all ways inside bounding box using dynamically built"
                    + " way linestring with way bbox indexing.");
            // The inner query selects the way id and node coordinates for
            // all ways constrained by the way bounding box which is
            // indexed.
            // The middle query converts the way node coordinates into
            // linestrings.
            // The outer query constrains the query to the linestrings
            // inside the bounding box. These aren't indexed but the inner
            // query way bbox constraint will minimise the unnecessary data.
            rowCount = jdbcTemplate.update("CREATE TEMPORARY TABLE bbox_ways ON COMMIT DROP AS" + " SELECT w.* FROM ("
                    + "SELECT c.id AS id, First(c.version) AS version, First(c.user_id) AS user_id,"
                    + " First(c.tstamp) AS tstamp, First(c.changeset_id) AS changeset_id, First(c.tags) AS tags,"
                    + " First(c.nodes) AS nodes, MakeLine(c.geom) AS way_line FROM ("
                    + "SELECT w.*, n.geom AS geom FROM nodes n" + " INNER JOIN way_nodes wn ON n.id = wn.node_id"
                    + " INNER JOIN ways w ON wn.way_id = w.id"
                    + " WHERE (w.bbox && ?) ORDER BY wn.way_id, wn.sequence_id" + ") c " + "GROUP BY c.id" + ") w "
                    + "WHERE (w.way_line && ?)", new PGgeometry(bboxPolygon), new PGgeometry(bboxPolygon));

        } else {
            LOG.finer("Selecting all way ids inside bounding box using already selected nodes.");
            // No way bbox support is available so select ways containing
            // the selected nodes.
            rowCount = jdbcTemplate.update("CREATE TEMPORARY TABLE bbox_ways ON COMMIT DROP AS"
                    + " SELECT w.* FROM ways w" + " INNER JOIN (" + " SELECT wn.way_id FROM way_nodes wn"
                    + " INNER JOIN bbox_nodes n ON wn.node_id = n.id GROUP BY wn.way_id"
                    + ") wids ON w.id = wids.way_id");
        }
        LOG.finer(rowCount + " rows affected.");

        LOG.finer("Adding a primary key to the temporary ways table.");
        jdbcTemplate.update("ALTER TABLE ONLY bbox_ways ADD CONSTRAINT pk_bbox_ways PRIMARY KEY (id)");

        LOG.finer("Updating query analyzer statistics on the temporary ways table.");
        jdbcTemplate.update("ANALYZE bbox_ways");

        // Select all relations containing the nodes or ways into the relation
        // table.
        LOG.finer("Selecting all relation ids containing selected nodes or ways.");
        rowCount = jdbcTemplate.update("CREATE TEMPORARY TABLE bbox_relations ON COMMIT DROP AS"
                + " SELECT r.* FROM relations r" + " INNER JOIN (" + "    SELECT relation_id FROM ("
                + "        SELECT rm.relation_id AS relation_id FROM relation_members rm"
                + "        INNER JOIN bbox_nodes n ON rm.member_id = n.id WHERE rm.member_type = 'N' "
                + "        UNION " + "        SELECT rm.relation_id AS relation_id FROM relation_members rm"
                + "        INNER JOIN bbox_ways w ON rm.member_id = w.id WHERE rm.member_type = 'W'"
                + "     ) rids GROUP BY relation_id" + ") rids ON r.id = rids.relation_id");
        LOG.finer(rowCount + " rows affected.");

        LOG.finer("Adding a primary key to the temporary relations table.");
        jdbcTemplate.update("ALTER TABLE ONLY bbox_relations ADD CONSTRAINT pk_bbox_relations PRIMARY KEY (id)");

        LOG.finer("Updating query analyzer statistics on the temporary relations table.");
        jdbcTemplate.update("ANALYZE bbox_relations");

        // Include all relations containing the current relations into the
        // relation table and repeat until no more inclusions occur.
        do {
            LOG.finer("Selecting parent relations of selected relations.");
            rowCount = jdbcTemplate.update("INSERT INTO bbox_relations " + "SELECT r.* FROM relations r INNER JOIN ("
                    + "    SELECT rm.relation_id FROM relation_members rm"
                    + "    INNER JOIN bbox_relations br ON rm.member_id = br.id"
                    + "    WHERE rm.member_type = 'R' AND NOT EXISTS ("
                    + "        SELECT * FROM bbox_relations br2 WHERE rm.relation_id = br2.id"
                    + "    ) GROUP BY rm.relation_id" + ") rids ON r.id = rids.relation_id");
            LOG.finer(rowCount + " rows affected.");
        } while (rowCount > 0);

        LOG.finer("Updating query analyzer statistics on the temporary relations table.");
        jdbcTemplate.update("ANALYZE bbox_relations");

        // If complete ways is set, select all nodes contained by the ways into
        // the node temp table.
        if (completeWays) {
            LOG.finer("Selecting all nodes for selected ways.");
            jdbcTemplate.update("CREATE TEMPORARY TABLE bbox_way_nodes (id bigint) ON COMMIT DROP");
            jdbcTemplate.queryForList("SELECT unnest_bbox_way_nodes()");
            jdbcTemplate.update("CREATE TEMPORARY TABLE bbox_missing_way_nodes ON COMMIT DROP AS "
                    + "SELECT buwn.id FROM (SELECT DISTINCT bwn.id FROM bbox_way_nodes bwn) buwn "
                    + "WHERE NOT EXISTS (" + "    SELECT * FROM bbox_nodes WHERE id = buwn.id" + ");");
            jdbcTemplate.update("ALTER TABLE ONLY bbox_missing_way_nodes"
                    + " ADD CONSTRAINT pk_bbox_missing_way_nodes PRIMARY KEY (id)");
            jdbcTemplate.update("ANALYZE bbox_missing_way_nodes");
            rowCount = jdbcTemplate.update("INSERT INTO bbox_nodes "
                    + "SELECT n.* FROM nodes n INNER JOIN bbox_missing_way_nodes bwn ON n.id = bwn.id;");
            LOG.finer(rowCount + " rows affected.");
        }

        LOG.finer("Updating query analyzer statistics on the temporary nodes table.");
        jdbcTemplate.update("ANALYZE bbox_nodes");

        // Create iterators for the selected records for each of the entity
        // types.
        LOG.finer("Iterating over results.");
        resultSets.add(new UpcastIterator<EntityContainer, BoundContainer>(new BoundContainerIterator(
                new ReleasableAdaptorForIterator<Bound>(bounds.iterator()))));
        resultSets.add(new UpcastIterator<EntityContainer, LastUpdateContainer>(new LastUpdateContainerIterator(
                new ReleasableAdaptorForIterator<LastUpdateTimestamp>(timestamps.iterator()))));
        resultSets.add(new UpcastIterator<EntityContainer, NodeContainer>(new NodeContainerIterator(nodeDao
                .iterate("bbox_"))));
        resultSets.add(new UpcastIterator<EntityContainer, WayContainer>(new WayContainerIterator(wayDao
                .iterate("bbox_"))));
        resultSets.add(new UpcastIterator<EntityContainer, RelationContainer>(new RelationContainerIterator(relationDao
                .iterate("bbox_"))));

        // Merge all readers into a single result iterator and return.
        return new MultipleSourceIterator<EntityContainer>(resultSets);
    }

    private Date fetchLastUpdate() {
        // TODO Auto-generated method stub
        return null;
    }

    /**
     * {@inheritDoc}
     */
    public void complete() {
        dbCtx.commitTransaction();
    }

    /**
     * {@inheritDoc}
     */
    public void release() {
        if (dbCtx != null) {
            dbCtx.release();

            dbCtx = null;
        }
    }

    public ReleasableIterator<EntityContainer> iterateSelectedNodes(List<Selector.BoundingBox> bboxSelectors,
            List<Selector> tagSelectors) {
        int rowCount;
        List<ReleasableIterator<EntityContainer>> resultSets = new ArrayList<ReleasableIterator<EntityContainer>>();

        if (!initialized) {
            initialize();
        }
        
        resultSets.add(new XapiPlanetLastUpdatedIterator(jdbcTemplate));

        String bboxWhereStr = buildBboxWhereClause(bboxSelectors);
        List<Object> bboxWhereObj = buildBboxWhereParameters(bboxSelectors);
        String tagsWhereStr = buildSelectorWhereClause(tagSelectors);
        List<Object> tagsWhereObj = buildSelectorWhereParameters(tagSelectors);
        List<Object> objArgs = new LinkedList<Object>();
        objArgs.addAll(bboxWhereObj);
        objArgs.addAll(tagsWhereObj);

        // PostgreSQL sometimes incorrectly chooses to perform full table scans,
        // these options
        // prevent this. Note that this is not recommended practice according to
        // documentation
        // but fixing this would require modifying the table statistics
        // gathering
        // configuration to produce better plans.
        jdbcTemplate.update("SET enable_seqscan = false");
        jdbcTemplate.update("SET enable_mergejoin = false");
        jdbcTemplate.update("SET enable_hashjoin = false");

        // Select all nodes inside the box into the node temp table.
        LOG.finer("Selecting all nodes inside bounding box.");
        rowCount = jdbcTemplate.update("CREATE TEMPORARY TABLE bbox_nodes ON COMMIT DROP AS"
                + " SELECT * FROM nodes WHERE " + bboxWhereStr + " AND " + tagsWhereStr, objArgs.toArray());

        LOG.finer("Adding a primary key to the temporary nodes table.");
        jdbcTemplate.update("ALTER TABLE ONLY bbox_nodes ADD CONSTRAINT pk_bbox_nodes PRIMARY KEY (id)");

        LOG.finer("Updating query analyzer statistics on the temporary nodes table.");
        jdbcTemplate.update("ANALYZE bbox_nodes");

        // Create iterators for the selected records for each of the entity
        // types.
        LOG.finer("Iterating over results.");
        resultSets.add(new UpcastIterator<EntityContainer, NodeContainer>(new NodeContainerIterator(nodeDao
                .iterate("bbox_"))));

        // Merge all readers into a single result iterator and return.
        return new MultipleSourceIterator<EntityContainer>(resultSets);
    }

    private List<Object> buildSelectorWhereParameters(List<Selector> tagSelectors) {
        List<Object> obj = new LinkedList<Object>();
        for (Selector selector : tagSelectors) {
            obj.addAll(selector.getWhereParam());
        }
        return obj;
    }

    private String buildSelectorWhereClause(List<Selector> tagSelectors) {
        StringBuilder obj = new StringBuilder();
        boolean first = true;
        for (Selector selector : tagSelectors) {
            if (!first) {
                obj.append(" OR ");
            }
            obj.append(selector.getWhereString());
            first = false;
        }
        if (first) {
            // empty selector, put in a null statement which postgres should
            // just optimise away
            obj.append("(1=1)");
        }
        return obj.toString();
    }

    private List<Object> buildBboxWhereParameters(List<Selector.BoundingBox> bboxSelectors) {
        List<Object> obj = new LinkedList<Object>();
        for (Selector selector : bboxSelectors) {
            obj.addAll(selector.getWhereParam());
        }
        return obj;
    }

    private String buildBboxWhereClause(List<Selector.BoundingBox> bboxSelectors) {
        StringBuilder obj = new StringBuilder();
        boolean first = true;
        for (Selector selector : bboxSelectors) {
            if (!first) {
                obj.append(" OR ");
            }
            obj.append(selector.getWhereString());
            first = false;
        }
        if (first) {
            // empty selector, put in a null statement which postgres should
            // just optimise away
            obj.append("(1=1)");
        }
        return obj.toString();
    }

    public ReleasableIterator<EntityContainer> iterateSelectedWays(List<Selector.BoundingBox> bboxSelectors,
            List<Selector> tagSelectors) {
        int rowCount;
        List<ReleasableIterator<EntityContainer>> resultSets = new ArrayList<ReleasableIterator<EntityContainer>>();
        ArrayList<Bound> bounds = new ArrayList<Bound>();

        if (!initialized) {
            initialize();
        }
        
        resultSets.add(new XapiPlanetLastUpdatedIterator(jdbcTemplate));

        String bboxWhereStr = buildBboxWhereClause(bboxSelectors).replaceAll("geom", "linestring");
        List<Object> bboxWhereObj = buildBboxWhereParameters(bboxSelectors);
        String tagsWhereStr = buildSelectorWhereClause(tagSelectors);
        List<Object> tagsWhereObj = buildSelectorWhereParameters(tagSelectors);
        List<Object> objArgs = new LinkedList<Object>();
        objArgs.addAll(bboxWhereObj);
        objArgs.addAll(tagsWhereObj);

        if (bboxSelectors.size() > 0) {
            Selector.BoundingBox boundingBox = bboxSelectors.get(0);
            double right = boundingBox.getRight();
            double left = boundingBox.getLeft();
            double top = boundingBox.getTop();
            double bottom = boundingBox.getBottom();
            bounds.add(new Bound(right, left, top, bottom, "Osmosis " + OsmosisConstants.VERSION));
        }

        // PostgreSQL sometimes incorrectly chooses to perform full table scans,
        // these options
        // prevent this. Note that this is not recommended practice according to
        // documentation
        // but fixing this would require modifying the table statistics
        // gathering
        // configuration to produce better plans.
        jdbcTemplate.update("SET enable_seqscan = false");
        jdbcTemplate.update("SET enable_mergejoin = false");
        jdbcTemplate.update("SET enable_hashjoin = false");

        LOG.finer("Creating empty nodes table.");
        rowCount = jdbcTemplate.update("CREATE TEMPORARY TABLE bbox_nodes ON COMMIT DROP AS"
                + " SELECT * FROM nodes WHERE FALSE");

        // Select all ways inside the bounding box into the way temp table.
        LOG.finer("Selecting all ways inside bounding box using way linestring geometry.");
        // We have full way geometry available so select ways
        // overlapping the requested bounding box.
        StringBuilder sql = new StringBuilder("CREATE TEMPORARY TABLE bbox_ways ON COMMIT DROP AS SELECT * FROM ways ");
        if (bboxWhereObj.size() > 0 || tagSelectors.size() > 0) {
            sql.append("WHERE ");
            if (bboxWhereObj.size() > 0) {
                sql.append("(");
                sql.append(bboxWhereStr);
                sql.append(")");
                if (tagSelectors.size() > 0) {
                    sql.append(" AND ");
                }
            }
            if (tagSelectors.size() > 0) {
                sql.append("(");
                sql.append(tagsWhereStr);
                sql.append(")");
            }
        }

        rowCount = jdbcTemplate.update(sql.toString(), objArgs.toArray());

        LOG.finer(rowCount + " rows affected.");

        LOG.finer("Adding a primary key to the temporary ways table.");
        jdbcTemplate.update("ALTER TABLE ONLY bbox_ways ADD CONSTRAINT pk_bbox_ways PRIMARY KEY (id)");

        LOG.finer("Updating query analyzer statistics on the temporary ways table.");
        jdbcTemplate.update("ANALYZE bbox_ways");

        LOG.finer("Selecting all nodes for selected ways.");
        jdbcTemplate.update("CREATE TEMPORARY TABLE bbox_way_nodes (id bigint) ON COMMIT DROP");
        jdbcTemplate.queryForList("SELECT unnest_bbox_way_nodes()");
        jdbcTemplate.update("CREATE TEMPORARY TABLE bbox_missing_way_nodes ON COMMIT DROP AS "
                + "SELECT buwn.id FROM (SELECT DISTINCT bwn.id FROM bbox_way_nodes bwn) buwn " + "WHERE NOT EXISTS ("
                + "    SELECT * FROM bbox_nodes WHERE id = buwn.id" + ");");
        jdbcTemplate.update("ALTER TABLE ONLY bbox_missing_way_nodes"
                + " ADD CONSTRAINT pk_bbox_missing_way_nodes PRIMARY KEY (id)");
        jdbcTemplate.update("ANALYZE bbox_missing_way_nodes");
        rowCount = jdbcTemplate.update("INSERT INTO bbox_nodes "
                + "SELECT n.* FROM nodes n INNER JOIN bbox_missing_way_nodes bwn ON n.id = bwn.id;");
        LOG.finer(rowCount + " rows affected.");

        LOG.finer("Updating query analyzer statistics on the temporary nodes table.");
        jdbcTemplate.update("ANALYZE bbox_nodes");

        // Create iterators for the selected records for each of the entity
        // types.
        LOG.finer("Iterating over results.");
        resultSets.add(new UpcastIterator<EntityContainer, BoundContainer>(new BoundContainerIterator(
                new ReleasableAdaptorForIterator<Bound>(bounds.iterator()))));
        resultSets.add(new UpcastIterator<EntityContainer, NodeContainer>(new NodeContainerIterator(nodeDao
                .iterate("bbox_"))));
        resultSets.add(new UpcastIterator<EntityContainer, WayContainer>(new WayContainerIterator(wayDao
                .iterate("bbox_"))));

        // Merge all readers into a single result iterator and return.
        return new MultipleSourceIterator<EntityContainer>(resultSets);
    }

    public ReleasableIterator<EntityContainer> iterateSelectedRelations(List<Selector.BoundingBox> bboxSelectors,
            List<Selector> tagSelectors) {
        int rowCount;
        List<ReleasableIterator<EntityContainer>> resultSets = new ArrayList<ReleasableIterator<EntityContainer>>();
        ArrayList<Bound> bounds = new ArrayList<Bound>();

        if (!initialized) {
            initialize();
        }
        
        resultSets.add(new XapiPlanetLastUpdatedIterator(jdbcTemplate));

        if (bboxSelectors.size() > 0) {
            Selector.BoundingBox boundingBox = bboxSelectors.get(0);
            double right = boundingBox.getRight();
            double left = boundingBox.getLeft();
            double top = boundingBox.getTop();
            double bottom = boundingBox.getBottom();
            bounds.add(new Bound(right, left, top, bottom, "Osmosis " + OsmosisConstants.VERSION));
        }

        String tagsWhereStr = buildSelectorWhereClause(tagSelectors);
        List<Object> tagsWhereObj = buildSelectorWhereParameters(tagSelectors);
        List<Object> objArgs = new LinkedList<Object>();
        objArgs.addAll(tagsWhereObj);

        // PostgreSQL sometimes incorrectly chooses to perform full table scans,
        // these options
        // prevent this. Note that this is not recommended practice according to
        // documentation
        // but fixing this would require modifying the table statistics
        // gathering
        // configuration to produce better plans.
        jdbcTemplate.update("SET enable_seqscan = false");
        jdbcTemplate.update("SET enable_mergejoin = false");
        jdbcTemplate.update("SET enable_hashjoin = false");

        LOG.finer("Selecting all relations matching tags.");
        rowCount = jdbcTemplate.update("CREATE TEMPORARY TABLE bbox_relations ON COMMIT DROP AS"
                + " SELECT * FROM relations WHERE " + tagsWhereStr, objArgs.toArray());

        LOG.finer("Adding a primary key to the temporary relations table.");
        jdbcTemplate.update("ALTER TABLE ONLY bbox_relations ADD CONSTRAINT pk_bbox_relations PRIMARY KEY (id)");

        LOG.finer("Updating query analyzer statistics on the temporary nodes table.");
        jdbcTemplate.update("ANALYZE bbox_relations");

        // Create iterators for the selected records for each of the entity
        // types.
        LOG.finer("Iterating over results.");
        resultSets.add(new UpcastIterator<EntityContainer, BoundContainer>(new BoundContainerIterator(
                new ReleasableAdaptorForIterator<Bound>(bounds.iterator()))));
        resultSets.add(new UpcastIterator<EntityContainer, RelationContainer>(new RelationContainerIterator(relationDao
                .iterate("bbox_"))));

        // Merge all readers into a single result iterator and return.
        return new MultipleSourceIterator<EntityContainer>(resultSets);
    }

    public ReleasableIterator<EntityContainer> iterateSelectedPrimitives(List<Selector.BoundingBox> bboxSelectors,
            List<Selector> tagSelectors) {
        ArrayList<Bound> bounds = new ArrayList<Bound>();
        int rowCount;
        List<ReleasableIterator<EntityContainer>> resultSets = new ArrayList<ReleasableIterator<EntityContainer>>();

        if (!initialized) {
            initialize();
        }
        
        resultSets.add(new XapiPlanetLastUpdatedIterator(jdbcTemplate));

        if (bboxSelectors.size() > 0) {
            Selector.BoundingBox boundingBox = bboxSelectors.get(0);
            double right = boundingBox.getRight();
            double left = boundingBox.getLeft();
            double top = boundingBox.getTop();
            double bottom = boundingBox.getBottom();
            bounds.add(new Bound(right, left, top, bottom, "Osmosis " + OsmosisConstants.VERSION));
        }

        String bboxWhereStr = buildBboxWhereClause(bboxSelectors);
        List<Object> bboxWhereObj = buildBboxWhereParameters(bboxSelectors);
        String tagsWhereStr = buildSelectorWhereClause(tagSelectors);
        List<Object> tagsWhereObj = buildSelectorWhereParameters(tagSelectors);
        List<Object> objArgs = new LinkedList<Object>();
        objArgs.addAll(bboxWhereObj);
        objArgs.addAll(tagsWhereObj);

        // PostgreSQL sometimes incorrectly chooses to perform full table scans,
        // these options
        // prevent this. Note that this is not recommended practice according to
        // documentation
        // but fixing this would require modifying the table statistics
        // gathering
        // configuration to produce better plans.
        jdbcTemplate.update("SET enable_seqscan = false");
        jdbcTemplate.update("SET enable_mergejoin = false");
        jdbcTemplate.update("SET enable_hashjoin = false");

        // Select all nodes inside the box into the node temp table.
        LOG.finer("Selecting all nodes inside bounding box.");
        StringBuilder sql = new StringBuilder(
                "CREATE TEMPORARY TABLE bbox_nodes ON COMMIT DROP AS SELECT * FROM nodes ");
        if (bboxWhereObj.size() > 0 || tagSelectors.size() > 0) {
            sql.append("WHERE ");
            if (bboxWhereObj.size() > 0) {
                sql.append("(");
                sql.append(bboxWhereStr);
                sql.append(")");
                if (tagSelectors.size() > 0) {
                    sql.append(" AND ");
                }
            }
            if (tagSelectors.size() > 0) {
                sql.append("(");
                sql.append(tagsWhereStr);
                sql.append(")");
            }
        }
        rowCount = jdbcTemplate.update(sql.toString(), objArgs.toArray());

        LOG.finer("Adding a primary key to the temporary nodes table.");
        jdbcTemplate.update("ALTER TABLE ONLY bbox_nodes ADD CONSTRAINT pk_bbox_nodes PRIMARY KEY (id)");

        LOG.finer("Updating query analyzer statistics on the temporary nodes table.");
        jdbcTemplate.update("ANALYZE bbox_nodes");

        // Select all ways inside the bounding box into the way temp table.
        LOG.finer("Selecting all ways inside bounding box using way linestring geometry.");
        // We have full way geometry available so select ways
        // overlapping the requested bounding box.
        sql = new StringBuilder("CREATE TEMPORARY TABLE bbox_ways ON COMMIT DROP AS SELECT * FROM ways ");
        if (bboxWhereObj.size() > 0 || tagSelectors.size() > 0) {
            sql.append("WHERE ");
            if (bboxWhereObj.size() > 0) {
                sql.append("(");
                sql.append(bboxWhereStr.replaceAll("geom", "linestring")); // FIXME
                sql.append(")");
                if (tagSelectors.size() > 0) {
                    sql.append(" AND ");
                }
            }
            if (tagSelectors.size() > 0) {
                sql.append("(");
                sql.append(tagsWhereStr);
                sql.append(")");
            }
        }
        rowCount = jdbcTemplate.update(sql.toString(), objArgs.toArray());

        LOG.finer(rowCount + " rows affected.");

        LOG.finer("Adding a primary key to the temporary ways table.");
        jdbcTemplate.update("ALTER TABLE ONLY bbox_ways ADD CONSTRAINT pk_bbox_ways PRIMARY KEY (id)");

        LOG.finer("Updating query analyzer statistics on the temporary ways table.");
        jdbcTemplate.update("ANALYZE bbox_ways");

        // Select all relations containing the nodes or ways into the relation
        // table.
        LOG.finer("Selecting all relation ids containing selected nodes or ways.");
        rowCount = jdbcTemplate.update("CREATE TEMPORARY TABLE bbox_relations ON COMMIT DROP AS"
                + " SELECT r.* FROM relations r" + " INNER JOIN (" + "    SELECT relation_id FROM ("
                + "        SELECT rm.relation_id AS relation_id FROM relation_members rm"
                + "        INNER JOIN bbox_nodes n ON rm.member_id = n.id WHERE rm.member_type = 'N' "
                + "        UNION " + "        SELECT rm.relation_id AS relation_id FROM relation_members rm"
                + "        INNER JOIN bbox_ways w ON rm.member_id = w.id WHERE rm.member_type = 'W'"
                + "     ) rids GROUP BY relation_id" + ") rids ON r.id = rids.relation_id");
        LOG.finer(rowCount + " rows affected.");

        LOG.finer("Adding a primary key to the temporary relations table.");
        jdbcTemplate.update("ALTER TABLE ONLY bbox_relations ADD CONSTRAINT pk_bbox_relations PRIMARY KEY (id)");

        LOG.finer("Updating query analyzer statistics on the temporary relations table.");
        jdbcTemplate.update("ANALYZE bbox_relations");

        // Include all relations containing the current relations into the
        // relation table and repeat until no more inclusions occur.
        do {
            LOG.finer("Selecting parent relations of selected relations.");
            rowCount = jdbcTemplate.update("INSERT INTO bbox_relations " + "SELECT r.* FROM relations r INNER JOIN ("
                    + "    SELECT rm.relation_id FROM relation_members rm"
                    + "    INNER JOIN bbox_relations br ON rm.member_id = br.id"
                    + "    WHERE rm.member_type = 'R' AND NOT EXISTS ("
                    + "        SELECT * FROM bbox_relations br2 WHERE rm.relation_id = br2.id"
                    + "    ) GROUP BY rm.relation_id" + ") rids ON r.id = rids.relation_id");
            LOG.finer(rowCount + " rows affected.");
        } while (rowCount > 0);

        LOG.finer("Updating query analyzer statistics on the temporary relations table.");
        jdbcTemplate.update("ANALYZE bbox_relations");

        // If complete ways is set, select all nodes contained by the ways into
        // the node temp table.
        LOG.finer("Selecting all nodes for selected ways.");
        jdbcTemplate.update("CREATE TEMPORARY TABLE bbox_way_nodes (id bigint) ON COMMIT DROP");
        jdbcTemplate.queryForList("SELECT unnest_bbox_way_nodes()");
        jdbcTemplate.update("CREATE TEMPORARY TABLE bbox_missing_way_nodes ON COMMIT DROP AS "
                + "SELECT buwn.id FROM (SELECT DISTINCT bwn.id FROM bbox_way_nodes bwn) buwn " + "WHERE NOT EXISTS ("
                + "    SELECT * FROM bbox_nodes WHERE id = buwn.id" + ");");
        jdbcTemplate.update("ALTER TABLE ONLY bbox_missing_way_nodes"
                + " ADD CONSTRAINT pk_bbox_missing_way_nodes PRIMARY KEY (id)");
        jdbcTemplate.update("ANALYZE bbox_missing_way_nodes");
        rowCount = jdbcTemplate.update("INSERT INTO bbox_nodes "
                + "SELECT n.* FROM nodes n INNER JOIN bbox_missing_way_nodes bwn ON n.id = bwn.id;");
        LOG.finer(rowCount + " rows affected.");

        LOG.finer("Updating query analyzer statistics on the temporary nodes table.");
        jdbcTemplate.update("ANALYZE bbox_nodes");

        // Create iterators for the selected records for each of the entity
        // types.
        LOG.finer("Iterating over results.");
        resultSets.add(new UpcastIterator<EntityContainer, BoundContainer>(new BoundContainerIterator(
                new ReleasableAdaptorForIterator<Bound>(bounds.iterator()))));
        resultSets.add(new UpcastIterator<EntityContainer, NodeContainer>(new NodeContainerIterator(nodeDao
                .iterate("bbox_"))));
        resultSets.add(new UpcastIterator<EntityContainer, WayContainer>(new WayContainerIterator(wayDao
                .iterate("bbox_"))));
        resultSets.add(new UpcastIterator<EntityContainer, RelationContainer>(new RelationContainerIterator(relationDao
                .iterate("bbox_"))));

        // Merge all readers into a single result iterator and return.
        return new MultipleSourceIterator<EntityContainer>(resultSets);
    }

    public ReleasableIterator<EntityContainer> iterateNodes(List<Long> ids) {
        int rowCount;
        List<ReleasableIterator<EntityContainer>> resultSets = new ArrayList<ReleasableIterator<EntityContainer>>();

        if (!initialized) {
            initialize();
        }
        
        resultSets.add(new XapiPlanetLastUpdatedIterator(jdbcTemplate));

        // PostgreSQL sometimes incorrectly chooses to perform full table scans,
        // these options
        // prevent this. Note that this is not recommended practice according to
        // documentation
        // but fixing this would require modifying the table statistics
        // gathering
        // configuration to produce better plans.
        jdbcTemplate.update("SET enable_seqscan = false");
        jdbcTemplate.update("SET enable_mergejoin = false");
        jdbcTemplate.update("SET enable_hashjoin = false");

        LOG.finer("Creating nodes table with single ID.");
        String idsSql = buildListSql(ids);
        rowCount = jdbcTemplate.update("CREATE TEMPORARY TABLE bbox_nodes ON COMMIT DROP AS"
                + " SELECT * FROM nodes WHERE id IN " + idsSql, ids.toArray());

        LOG.finer("Updating query analyzer statistics on the temporary nodes table.");
        jdbcTemplate.update("ANALYZE bbox_nodes");

        // Create iterators for the selected records for each of the entity
        // types.
        LOG.finer("Iterating over results.");
        resultSets.add(new UpcastIterator<EntityContainer, NodeContainer>(new NodeContainerIterator(nodeDao
                .iterate("bbox_"))));

        // Merge all readers into a single result iterator and return.
        return new MultipleSourceIterator<EntityContainer>(resultSets);

    }

    public ReleasableIterator<EntityContainer> iterateWays(List<Long> ids) {
        int rowCount;
        List<ReleasableIterator<EntityContainer>> resultSets = new ArrayList<ReleasableIterator<EntityContainer>>();

        if (!initialized) {
            initialize();
        }
        
        resultSets.add(new XapiPlanetLastUpdatedIterator(jdbcTemplate));

        // PostgreSQL sometimes incorrectly chooses to perform full table scans,
        // these options
        // prevent this. Note that this is not recommended practice according to
        // documentation
        // but fixing this would require modifying the table statistics
        // gathering
        // configuration to produce better plans.
        jdbcTemplate.update("SET enable_seqscan = false");
        jdbcTemplate.update("SET enable_mergejoin = false");
        jdbcTemplate.update("SET enable_hashjoin = false");

        LOG.finer("Creating empty nodes table.");
        rowCount = jdbcTemplate.update("CREATE TEMPORARY TABLE bbox_nodes ON COMMIT DROP AS"
                + " SELECT * FROM nodes WHERE FALSE");

        String idsSql = buildListSql(ids);
        rowCount = jdbcTemplate.update(
                "CREATE TEMPORARY TABLE bbox_ways ON COMMIT DROP AS SELECT * FROM ways WHERE id IN " + idsSql,
                ids.toArray());

        LOG.finer(rowCount + " rows affected.");

        LOG.finer("Updating query analyzer statistics on the temporary ways table.");
        jdbcTemplate.update("ANALYZE bbox_ways");

        LOG.finer("Selecting all nodes for selected ways.");
        jdbcTemplate.update("CREATE TEMPORARY TABLE bbox_way_nodes (id bigint) ON COMMIT DROP");
        jdbcTemplate.queryForList("SELECT unnest_bbox_way_nodes()");
        jdbcTemplate.update("CREATE TEMPORARY TABLE bbox_missing_way_nodes ON COMMIT DROP AS "
                + "SELECT buwn.id FROM (SELECT DISTINCT bwn.id FROM bbox_way_nodes bwn) buwn " + "WHERE NOT EXISTS ("
                + "    SELECT * FROM bbox_nodes WHERE id = buwn.id" + ");");
        jdbcTemplate.update("ALTER TABLE ONLY bbox_missing_way_nodes"
                + " ADD CONSTRAINT pk_bbox_missing_way_nodes PRIMARY KEY (id)");
        jdbcTemplate.update("ANALYZE bbox_missing_way_nodes");
        rowCount = jdbcTemplate.update("INSERT INTO bbox_nodes "
                + "SELECT n.* FROM nodes n INNER JOIN bbox_missing_way_nodes bwn ON n.id = bwn.id;");
        LOG.finer(rowCount + " rows affected.");

        LOG.finer("Updating query analyzer statistics on the temporary nodes table.");
        jdbcTemplate.update("ANALYZE bbox_nodes");

        // Create iterators for the selected records for each of the entity
        // types.
        LOG.finer("Iterating over results.");
        resultSets.add(new UpcastIterator<EntityContainer, NodeContainer>(new NodeContainerIterator(nodeDao
                .iterate("bbox_"))));
        resultSets.add(new UpcastIterator<EntityContainer, WayContainer>(new WayContainerIterator(wayDao
                .iterate("bbox_"))));

        // Merge all readers into a single result iterator and return.
        return new MultipleSourceIterator<EntityContainer>(resultSets);
    }

    private String buildListSql(List<Long> ids) {
        StringBuilder idsSql = new StringBuilder("(");
        Iterator<Long> iterator = ids.iterator();
        while (iterator.hasNext()) {
            iterator.next();
            idsSql.append("?");
            if (iterator.hasNext()) {
                idsSql.append(", ");
            }
        }
        idsSql.append(")");
        return idsSql.toString();
    }

    public ReleasableIterator<EntityContainer> iterateRelations(List<Long> ids) {
        int rowCount;
        List<ReleasableIterator<EntityContainer>> resultSets = new ArrayList<ReleasableIterator<EntityContainer>>();

        if (!initialized) {
            initialize();
        }
        
        resultSets.add(new XapiPlanetLastUpdatedIterator(jdbcTemplate));

        // PostgreSQL sometimes incorrectly chooses to perform full table scans,
        // these options
        // prevent this. Note that this is not recommended practice according to
        // documentation
        // but fixing this would require modifying the table statistics
        // gathering
        // configuration to produce better plans.
        jdbcTemplate.update("SET enable_seqscan = false");
        jdbcTemplate.update("SET enable_mergejoin = false");
        jdbcTemplate.update("SET enable_hashjoin = false");

        LOG.finer("Creating nodes table with single ID.");
        String idsSql = buildListSql(ids);
        rowCount = jdbcTemplate.update("CREATE TEMPORARY TABLE bbox_relations ON COMMIT DROP AS"
                + " SELECT * FROM relations WHERE id IN " + idsSql, ids.toArray());

        LOG.finer("Updating query analyzer statistics on the temporary nodes table.");
        jdbcTemplate.update("ANALYZE bbox_relations");

        // Create iterators for the selected records for each of the entity
        // types.
        LOG.finer("Iterating over results.");
        resultSets.add(new UpcastIterator<EntityContainer, RelationContainer>(new RelationContainerIterator(relationDao
                .iterate("bbox_"))));

        // Merge all readers into a single result iterator and return.
        return new MultipleSourceIterator<EntityContainer>(resultSets);

    }
}
