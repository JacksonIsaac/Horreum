package io.hyperfoil.tools.horreum.api;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import javax.annotation.security.PermitAll;
import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import javax.persistence.EntityManager;
import javax.persistence.Query;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.OPTIONS;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import io.hyperfoil.tools.horreum.entity.alerting.Change;
import io.hyperfoil.tools.horreum.entity.alerting.DataPoint;
import io.hyperfoil.tools.horreum.entity.alerting.Variable;
import io.hyperfoil.tools.horreum.grafana.Target;
import io.quarkus.security.identity.SecurityIdentity;

/**
 * This service works as a backend for calls from Grafana (using
 * <a href="https://grafana.com/grafana/plugins/simpod-json-datasource">simpod-json-datasource</a>)
 * since Horreum exposes charts as embedded Grafana panels.
 */
@ApplicationScoped
@Path("/api/grafana")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class GrafanaService {
   @Inject
   SqlService sqlService;

   @Inject
   SecurityIdentity identity;

   @Inject
   EntityManager em;

   @PermitAll
   @GET
   @Path("/")
   public Response healthcheck() {
      return Response.ok().build();
   }

   @PermitAll
   @POST
   @Path("/search")
   public Object[] search(Target query) {
      try (@SuppressWarnings("unused") CloseMe closeMe = sqlService.withRoles(em, identity)) {
         return Variable.<Variable>listAll().stream().map(v -> String.valueOf(v.id)).toArray();
      }
   }

   @PermitAll
   @POST
   @Path("/query")
   public Response query(@Context HttpServletRequest request, Query query) {
      List<TimeseriesTarget> result = new ArrayList<>();
      try (@SuppressWarnings("unused") CloseMe closeMe = sqlService.withRoles(em, identity)) {
         for (Target target : query.targets) {
            if (target.type != null && !target.type.equals("timeseries")) {
               return Response.status(Response.Status.BAD_REQUEST).entity("Tables are not implemented").build();
            }
            String tq = target.target;
            Map<String, String> tags = null;
            int semicolon = tq.indexOf(';');
            if (semicolon >= 0) {
               tags = parseTags(tq, semicolon);
               tq = tq.substring(0, semicolon);
            }
            int variableId = parseVariableId(tq);
            if (variableId < 0) {
               return Response.status(Response.Status.BAD_REQUEST).entity("Target must be variable ID").build();
            }
            Variable variable = Variable.findById(variableId);
            String variableName = String.valueOf(variableId);
            if (variable != null) {
               variableName = variable.name;
            }
            TimeseriesTarget tt = new TimeseriesTarget();
            tt.target = variableName;
            result.add(tt);

            StringBuilder sql = new StringBuilder("SELECT datapoint.* FROM datapoint ");
            if (tags != null) {
               sql.append(" JOIN run_tags ON run_tags.runid = datapoint.runid ");
            }
            sql.append(" WHERE variable_id = ?1 AND timestamp BETWEEN ?2 AND ?3 ");
            addTagQuery(tags, sql);
            sql.append(" ORDER BY timestamp ASC");
            javax.persistence.Query nativeQuery = em.createNativeQuery(sql.toString(), DataPoint.class)
                  .setParameter(1, variableId)
                  .setParameter(2, query.range.from)
                  .setParameter(3, query.range.to);
            addTagValues(tags, nativeQuery);
            for (DataPoint dp : (List<DataPoint>) nativeQuery.getResultList()) {
               tt.datapoints.add(new Number[] { dp.value, dp.timestamp.toEpochMilli() });
            }
         }
      }
      return Response.ok(result).build();
   }

   private void addTagQuery(Map<String, String> tags, StringBuilder sql) {
      if (tags != null) {
         int counter = 4;
         for (String tag : tags.keySet()) {
            sql.append(" AND jsonb_path_query_first(run_tags.tags, '$.").append(tag).append("'::::jsonpath)#>>'{}' = ?").append(counter++);
         }
      }
   }

   private void addTagValues(Map<String, String> tags, javax.persistence.Query nativeQuery) {
      if (tags != null) {
         int counter = 4;
         for (String value : tags.values()) {
            nativeQuery.setParameter(counter++, value);
         }
      }
   }

   private Map<String, String> parseTags(String tq, int semicolon) {
      Map<String, String> tags = new TreeMap<>();
      for (String keyValue : tq.substring(semicolon + 1).split(";")) {
         int colon = keyValue.indexOf(":");
         if (colon < 0) continue;
         tags.put(keyValue.substring(0, colon), keyValue.substring(colon + 1));
      }
      return tags;
   }

   private int parseVariableId(String target) {
      int variableId;
      try {
         variableId = Integer.parseInt(target);
      } catch (NumberFormatException e) {
         // TODO: support test name/variable name?
         variableId = -1;
      }
      return variableId;
   }

   @PermitAll
   @OPTIONS
   @Path("/annotations")
   public Response annotations() {
      return Response.ok()
            .header("Access-Control-Allow-Headers", "accept, content-type")
            .header("Access-Control-Allow-Methods", "POST")
            .header("Access-Control-Allow-Origin", "*")
            .build();
   }

   @PermitAll
   @POST
   @Path("/annotations")
   public Response annotations(AnnotationsQuery query) {
      // Note that annotations are per-dashboard, not per-panel:
      // https://github.com/grafana/grafana/issues/717
      List<AnnotationDefinition> annotations = new ArrayList<>();
      String tq = query.annotation.query;
      Map<String, String> tags = null;
      int semicolon = tq.indexOf(';');
      if (semicolon >= 0) {
         tags = parseTags(tq, semicolon);
         tq = tq.substring(0, semicolon);
      }
      int variableId = parseVariableId(tq);
      if (variableId < 0) {
         return Response.status(Response.Status.BAD_REQUEST).entity("Query must be variable ID").build();
      }
      // TODO: use identity forwarded
      try (@SuppressWarnings("unused") CloseMe closeMe = sqlService.withRoles(em, identity)) {
         StringBuilder sql = new StringBuilder("SELECT change.* FROM change ");
         if (tags != null) {
            sql.append(" JOIN run_tags ON run_tags.runid = change.runid ");
         }
         sql.append(" WHERE variable_id = ?1 AND timestamp BETWEEN ?2 AND ?3 ");
         addTagQuery(tags, sql);
         javax.persistence.Query nativeQuery = em.createNativeQuery(sql.toString(), Change.class)
               .setParameter(1, variableId)
               .setParameter(2, query.range.from)
               .setParameter(3, query.range.to);
         addTagValues(tags, nativeQuery);

         for (Change change : (List<Change>) nativeQuery.getResultList()) {
            annotations.add(createAnnotation(change));
         }
      }
      return Response.ok(annotations).build();
   }

   private AnnotationDefinition createAnnotation(Change change) {
      StringBuilder content = new StringBuilder("Variable: ").append(change.variable.name);
      if (change.variable.group != null) {
         content.append(" (group ").append(change.variable.group).append(")");
      }
      content.append("<br>").append(change.description).append("<br>Confirmed: ").append(change.confirmed);
      return new AnnotationDefinition("Change in run " + change.runId, content.toString(), false, change.timestamp.toEpochMilli(), 0, new String[0]);
   }

   public static class Query {
      public Range range;
      public List<Target> targets;
   }

   public static class Range {
      public Instant from;
      public Instant to;
   }

   public static class TimeseriesTarget {
      public String target;
      public List<Number[]> datapoints = new ArrayList<>();
   }

   public static class AnnotationsQuery {
      public Range range;
      public AnnotationQuery annotation;
   }

   public static class AnnotationQuery {
      public String name;
      public String datasource;
      public String iconColor;
      public boolean enable;
      public String query;
   }

   public static class AnnotationDefinition {
      public String title;
      public String text;
      public boolean isRegion;
      public long time;
      public long timeEnd;
      public String[] tags;

      public AnnotationDefinition(String title, String text, boolean isRegion, long time, long timeEnd, String[] tags) {
         this.title = title;
         this.text = text;
         this.isRegion = isRegion;
         this.time = time;
         this.timeEnd = timeEnd;
         this.tags = tags;
      }
   }

}
