package com.truesim.samples.showcase.flora;

import com.truesim.common.math.layout.ElementBuffer;
import com.truesim.common.math.layout.Vec2Attribute;
import com.truesim.common.math.layout.Vec3Attribute;
import com.truesim.common.math.layout.Vec4Attribute;
import com.truesim.common.procedural.assets.ProceduralAssets;
import com.truesim.common.procedural.assets.Recipe;
import com.truesim.common.procedural.assets.SceneMeshes;
import com.truesim.common.procedural.mesh.ProceduralMesh;
import com.truesim.common.procedural.mesh.ProceduralVertex;
import com.truesim.scene.assets.AssetRef;
import com.truesim.scene.assets.Generator;
import com.truesim.scene.assets.PathAssets;
import com.truesim.scene.assets.mesh.MeshData;
import com.truesim.scene.assets.mesh.VertexAttribute;
import com.truesim.scene.meshbuild.MeshBuilder;
import com.truesim.scene.render.MaterialData;
import com.truesim.scene.render.ModelData;
import com.truesim.scene.render.RenderAssets;
import java.util.ArrayList;
import java.util.List;

/**
 * common-procedural's trees and plants as models whose mesh is a cluster hierarchy, so the engine draws each meshlet
 * at the detail its distance needs. The library's own models are its level 0 as one flat level (every tree drawn
 * whole at any distance: a valley's forest overflowed the views' lists by far); here a tree starts from its generated
 * level 1 (some 25,000 triangles), a plant from its level 0, and scene-meshbuild simplifies from there.
 */
final class FloraModels {

    private final ProceduralAssets procedural;
    private final PathAssets paths;
    private final Generator<Recipe, MeshData> meshes;
    private final Generator<Recipe, ModelData> models;

    FloraModels(ProceduralAssets procedural, PathAssets paths) {
        this.procedural = procedural;
        this.paths = paths;
        meshes = (recipe, seed) -> hierarchy(level(recipe));
        models = (recipe, seed) -> model(recipe);
    }

    /** The model of {@code recipe}. */
    AssetRef<ModelData> model(Recipe recipe, long seed) {
        return AssetRef.generated(RenderAssets.MODEL, models, recipe, seed);
    }

    private ProceduralMesh level(Recipe recipe) throws Exception {
        List<ProceduralMesh> all = procedural.meshes(recipe);
        return all.get("tree".equals(recipe.generator()) && all.size() > 2 ? 1 : 0);
    }

    private ModelData model(Recipe recipe) throws Exception {
        ProceduralMesh mesh = level(recipe);
        List<AssetRef<MaterialData>> materials = new ArrayList<>();
        for (int s = 0; s < mesh.submeshCount(); s++) {
            if (SceneMeshes.kept(mesh, s) && s < recipe.materials().size() && !recipe.materials().get(s).isEmpty()) {
                materials.add(paths.ref(RenderAssets.MATERIAL, recipe.materials().get(s)));
            }
        }
        AssetRef<MeshData> ref = AssetRef.generated(RenderAssets.MESH, meshes, recipe, recipe.seed());
        return new ModelData(List.of(ModelData.Node.of(recipe.generator() + ":" + recipe.preset(), -1, ref,
            materials)));
    }

    /** {@code mesh} with its wind data, as a cluster hierarchy (as {@code SceneMeshes.build} makes it flat). */
    static MeshData hierarchy(ProceduralMesh mesh) {
        int n = mesh.vertexCount();
        ElementBuffer vertices = mesh.vertices();
        Vec3Attribute normal = vertices.attribute(ProceduralVertex.NORMAL);
        Vec3Attribute tangent = vertices.attribute(ProceduralVertex.TANGENT);
        Vec2Attribute uv = vertices.attribute(ProceduralVertex.UV);
        Vec2Attribute pivot = vertices.attribute(ProceduralVertex.WIND_PIVOT);
        Vec4Attribute weights = vertices.attribute(ProceduralVertex.WIND);
        float[] normals = new float[3 * n];
        float[] tangents = new float[4 * n];
        float[] uv0 = new float[2 * n];
        float[] uv1 = new float[2 * n];
        float[] colors = new float[4 * n];
        for (int i = 0; i < n; i++) {
            normals[3 * i] = (float) normal.x(i);
            normals[3 * i + 1] = (float) normal.y(i);
            normals[3 * i + 2] = (float) normal.z(i);
            tangents[4 * i] = (float) tangent.x(i);
            tangents[4 * i + 1] = (float) tangent.y(i);
            tangents[4 * i + 2] = (float) tangent.z(i);
            tangents[4 * i + 3] = 1;
            uv0[2 * i] = (float) uv.x(i);
            uv0[2 * i + 1] = (float) uv.y(i);
            uv1[2 * i] = (float) pivot.x(i);
            uv1[2 * i + 1] = (float) pivot.y(i);
            colors[4 * i] = (float) weights.x(i);
            colors[4 * i + 1] = (float) weights.y(i);
            colors[4 * i + 2] = (float) weights.z(i);
            colors[4 * i + 3] = (float) weights.w(i);
        }
        MeshBuilder builder = new MeshBuilder(SceneMeshes.FORMAT, n).positions(mesh.positions())
            .attribute(VertexAttribute.NORMAL, normals).attribute(VertexAttribute.TANGENT, tangents)
            .attribute(VertexAttribute.UV0, uv0).attribute(VertexAttribute.UV1, uv1)
            .attribute(VertexAttribute.COLOR, colors);
        for (int s = 0; s < mesh.submeshCount(); s++) {
            if (SceneMeshes.kept(mesh, s)) {
                builder.submesh(mesh.submeshIndices(s));
            }
        }
        return builder.buildHierarchy();
    }
}
