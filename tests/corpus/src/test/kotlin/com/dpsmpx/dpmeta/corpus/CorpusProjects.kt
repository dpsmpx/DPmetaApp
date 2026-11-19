package com.dpsmpx.dpmeta.corpus

import com.dpsmpx.dpai.core.serialization.JsonParser
import com.dpsmpx.dpmeta.model.ProjectCatalog
import com.dpsmpx.dpmeta.model.ProjectList
import java.nio.file.Files
import java.nio.file.Path

/** Синтетический список пользователя, без ролей и перенаправлений (D-176). */
internal fun corpusProjects(root: Path): ProjectCatalog = ProjectList.parse(
    JsonParser().parse(Files.readString(root.resolve("projects.json"))).valueOrNull()!!,
).valueOrNull()!!.forSync()
