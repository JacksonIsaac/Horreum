package io.hyperfoil.tools.horreum.entity.json;

import io.hyperfoil.tools.horreum.entity.converter.AccessSerializer;
import io.hyperfoil.tools.horreum.entity.converter.InstantSerializer;
import io.hyperfoil.tools.yaup.json.Json;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.runtime.annotations.RegisterForReflection;
import org.hibernate.annotations.Type;

import javax.json.bind.annotation.JsonbTypeDeserializer;
import javax.json.bind.annotation.JsonbTypeSerializer;
import javax.persistence.*;
import javax.validation.constraints.NotNull;
import java.time.Instant;

@Entity(name = "run")
@RegisterForReflection
public class Run extends PanacheEntityBase {
   public static final String EVENT_NEW = "run/new";
   public static final String EVENT_TRASHED = "run/trashed";

   @Id
   @SequenceGenerator(
      name = "runSequence",
      sequenceName = "run_id_seq",
      allocationSize = 1,
      initialValue = 1)
   @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "runSequence")
   public Integer id;

   @NotNull
   @Column(name="start", columnDefinition = "timestamp")
   @JsonbTypeDeserializer(InstantSerializer.class)
   @JsonbTypeSerializer(InstantSerializer.class)
   public Instant start;

   @NotNull
   @Column(name="stop", columnDefinition = "timestamp")
   @JsonbTypeDeserializer(InstantSerializer.class)
   @JsonbTypeSerializer(InstantSerializer.class)
   public Instant stop;

   public String description;

   @NotNull
   public Integer testid;

   @NotNull
   @Type(type = "io.hyperfoil.tools.horreum.entity.converter.JsonUserType")
   public Json data;

   @NotNull
   public String owner;

   public String token;

   @NotNull
   @JsonbTypeSerializer(AccessSerializer.class)
   @JsonbTypeDeserializer(AccessSerializer.class)
   public Access access = Access.PUBLIC;

   @NotNull
   @Column(columnDefinition = "boolean default false")
   public boolean trashed;
}
