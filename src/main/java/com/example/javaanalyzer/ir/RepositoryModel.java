package com.example.javaanalyzer.ir;

/**
 * A repository bound to an entity, e.g. an interface extending {@code JpaRepository<E, ID>}.
 *
 * @param baseType the extended repository type as written
 * @param entityType null when the type arguments are not given
 * @param idType null when the type arguments are not given
 */
public record RepositoryModel(
    String typeName, TypeRef baseType, TypeRef entityType, TypeRef idType) {}
