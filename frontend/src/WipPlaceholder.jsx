const WIP_IMAGE_URL = 'https://encrypted-tbn0.gstatic.com/images?q=tbn:ANd9GcQ37N3dKpCdWiQ7oUheXZT4BPX4r4CK05Ac7TC3LDAQng&s=10'

export default function WipPlaceholder({ label, caption }) {
  return (
    <div className="wip-placeholder">
      <div className="wip-image-frame">
        <img src={WIP_IMAGE_URL} alt="Under construction" className="wip-image" />
      </div>
      <div className="wip-danger-box">Danger : WIP</div>
      <p className="wip-caption">{caption || `"${label}" hasn't been built yet.`}</p>
    </div>
  )
}
