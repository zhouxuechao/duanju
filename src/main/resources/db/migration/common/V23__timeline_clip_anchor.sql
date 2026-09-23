ALTER TABLE "timeline_item" ADD COLUMN linked_video_timeline_item_id UUID;
ALTER TABLE "timeline_item" ADD CONSTRAINT fk_timeline_item_linked_video FOREIGN KEY (linked_video_timeline_item_id) REFERENCES "timeline_item"(id);
CREATE INDEX ix_timeline_item_linked_video ON "timeline_item"(linked_video_timeline_item_id);
