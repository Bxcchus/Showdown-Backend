package lol.pinkward.showdown.match;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface SeasonRatingArchiveRepository extends JpaRepository<SeasonRatingArchive, UUID> {}
