#[cfg(test)]
mod tests {
    use crate::parser::api::parse_vote_tag;
    use crate::parser::archive::{parse_archive_url, parse_archives_with_funds};
    use crate::parser::detail::{parse_gallery_detail, parse_gallery_version_candidates};
    use crate::parser::list::parse_info_list;
    use crate::parser::profile::{parse_profile, parse_profile_url};
    use reqwest::get;
    use std::fs;
    use tl::ParserOptions;

    #[test]
    fn gnd_parses_identity_title_and_added_time() {
        let dom = tl::parse(
            include_str!("../../test_data/gallery_versions.html"),
            ParserOptions::default().track_ids(),
        )
        .unwrap();
        let items = parse_gallery_version_candidates(&dom, dom.parser()).unwrap();
        assert_eq!(items.len(), 2);
        assert_eq!(items[0].gid, 42);
        assert_eq!(items[0].token, "0123456789");
        assert_eq!(items[0].title, "A & B");
        assert_eq!(items[0].posted, "2024-01-02 03:04");
        assert_eq!(items[1].gid, 7);
        assert_eq!(items[1].token, "abcdef0123");
        assert_eq!(items[1].posted, "2024-02-03 04:05");
    }

    #[test]
    fn gnd_empty_is_a_successful_empty_list() {
        let dom = tl::parse(
            include_str!("../../test_data/gallery_versions_empty.html"),
            ParserOptions::default().track_ids(),
        )
        .unwrap();
        assert!(
            parse_gallery_version_candidates(&dom, dom.parser())
                .unwrap()
                .is_empty()
        );
    }

    #[test]
    fn gnd_missing_is_not_an_empty_list() {
        let dom = tl::parse(
            include_str!("../../test_data/gallery_versions_missing.html"),
            ParserOptions::default().track_ids(),
        )
        .unwrap();
        assert!(parse_gallery_version_candidates(&dom, dom.parser()).is_none());
    }

    #[test]
    fn gnd_ignores_invalid_links_and_decodes_title_entities() {
        let dom = tl::parse(
            include_str!("../../test_data/gallery_versions.html"),
            ParserOptions::default().track_ids(),
        )
        .unwrap();
        let items = parse_gallery_version_candidates(&dom, dom.parser()).unwrap();
        assert_eq!(
            items.iter().map(|item| item.gid).collect::<Vec<_>>(),
            vec![42, 7]
        );
        assert_eq!(items[1].title, "日本 \"edition\"");
    }

    #[test]
    fn gnd_cbor_keeps_missing_and_valid_empty_distinct() {
        let empty = tl::parse(
            include_str!("../../test_data/gallery_versions_empty.html"),
            ParserOptions::default().track_ids(),
        )
        .unwrap();
        let missing = tl::parse(
            include_str!("../../test_data/gallery_versions_missing.html"),
            ParserOptions::default().track_ids(),
        )
        .unwrap();
        let empty =
            serde_cbor::value::to_value(parse_gallery_version_candidates(&empty, empty.parser()))
                .unwrap();
        let missing = serde_cbor::value::to_value(parse_gallery_version_candidates(
            &missing,
            missing.parser(),
        ))
        .unwrap();
        assert_eq!(empty, serde_cbor::Value::Array(Vec::new()));
        assert_eq!(missing, serde_cbor::Value::Null);
    }

    #[tokio::test]
    async fn test_parse_info() {
        let resp = get("https://e-hentai.org/").await.expect("Failed to get!");
        let body = resp.text().await.expect("Failed to receive!");
        let dom = tl::parse(&body, ParserOptions::default()).expect("Failed to parse html");
        let result = parse_info_list(&dom, dom.parser()).expect("Failed to parse info list");
        dbg!(result);
    }

    #[test]
    fn test_parse_archives_with_funds() {
        let body = fs::read_to_string("test_data/archives_with_funds.html")
            .expect("Failed to read html file");
        let dom = tl::parse(&body, ParserOptions::default()).expect("Failed to parse html");
        let result =
            parse_archives_with_funds(&dom, dom.parser(), &body).expect("Failed to parse archives");
        dbg!(result);
    }

    #[test]
    fn test_parse_archive_url() {
        let body =
            fs::read_to_string("test_data/archive_url.html").expect("Failed to read html file");
        let dom = tl::parse(&body, ParserOptions::default()).expect("Failed to parse html");
        let result =
            parse_archive_url(&dom, dom.parser(), &body).expect("Failed to parse archives");
        assert_eq!(result, Some("https://0?start=1".to_string()));
    }

    #[tokio::test]
    async fn test_parse_profile() {
        let body =
            fs::read_to_string("test_data/profile_url.html").expect("Failed to read html file");
        let dom = tl::parse(&body, ParserOptions::default()).expect("Failed to parse html");
        let result = parse_profile_url(&dom, dom.parser()).expect("Failed to parse profile url");
        let resp = get(result).await.expect("Failed to get!");
        let body = resp.text().await.expect("Failed to receive!");
        let dom = tl::parse(&body, ParserOptions::default()).expect("Failed to parse html");
        let result = parse_profile(&dom, dom.parser()).expect("Failed to parse profile");
        assert_eq!(result.displayName, "Tenboro".to_string());
        assert_eq!(
            result.avatar,
            Some("https://forums.e-hentai.org/ehgt/jdk_180.png".to_string())
        );
    }

    #[tokio::test]
    async fn test_parse_gallery_detail() {
        let resp = get("https://e-hentai.org/g/530350/8b3c7e4a21/")
            .await
            .expect("Failed to get!");
        let body = resp.text().await.expect("Failed to receive!");
        let mut dom =
            tl::parse(&body, ParserOptions::default().track_ids()).expect("Failed to parse HTML");
        let result = parse_gallery_detail(&mut dom, &body).expect("Failed to parse gallery detail");
        dbg!(result);
    }

    #[test]
    fn test_parse_tag_gallery() {
        let json = fs::read_to_string("test_data/vote_tag.json").expect("Failed to read json file");
        let result = parse_vote_tag(&json).expect_err("Should fail to parse");
        dbg!(result);
    }
}
